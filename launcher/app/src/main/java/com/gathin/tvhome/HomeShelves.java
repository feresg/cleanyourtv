package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * home_shelves: rows of pictures under the apps, each read from a module whose card the owner chose to show
 * (docs/specs/shelves.md). The card's activity names a content provider of its own app in the {@link #META}
 * meta-data; the launcher reads {@code content://<authority>/shelves} (columns id, title) and
 * {@code .../shelves/<id>} (id, title, subtitle, progress, image), and OK starts the card's activity with
 * {@link #EXTRA_ITEM}. The launcher has no network: a picture is a content:// URI of that same provider.
 *
 * Like {@link Cards}, the part that reads what a module hands over lives here with no UI code: every value is
 * bounded and cleaned, and a module that fails is left out with one log line. The checks are plain character
 * loops (no regular expressions), so the JVM tests run them as the TV does.
 */
final class HomeShelves {
    static final String META = "com.gathin.tvhome.shelves";
    static final String EXTRA_ITEM = "com.gathin.tvhome.extra.SHELF_ITEM";
    /** logcat tag for every module left out: `logcat -s TvHomeShelves`. */
    static final String TAG = "TvHomeShelves";

    /** Rows on Home in all, and titles in one row. */
    static final int MAX_ROWS = 4;
    static final int MAX_ITEMS = 20;
    static final int ID_MAX = 128;
    static final int AUTHORITY_MAX = 128;
    static final int HEADING_MAX = 48;
    static final int TITLE_MAX = 48;
    static final int SUBTITLE_MAX = 48;
    static final int IMAGE_URI_MAX = 256;
    /** A picture file bigger than this is not read: a card's picture is a few tens of KB. */
    static final int IMAGE_MAX_BYTES = 1024 * 1024;
    /** Nor one that would decode to more pixels than this before sampling (a small file can be a huge picture). */
    static final long IMAGE_MAX_PIXELS = 4096L * 4096L;

    /** One title in a row. */
    static final class Item {
        /** Handed back to the module in EXTRA_ITEM when the title is opened. */
        final String id;
        final String title;
        /** The line under the title, or "". */
        final String subtitle;
        /** How much of it was watched, 0 to 100, or -1 for no bar. */
        final int progress;
        /** A content:// URI of the row's own provider, or null. */
        final String image;
        /** The decoded picture, or null when there is none or it could not be read. */
        final Bitmap picture;

        Item(String id, String title, String subtitle, int progress, String image, Bitmap picture) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.progress = progress;
            this.image = image;
            this.picture = picture;
        }
    }

    /** One row: a heading and its titles, from one module. */
    static final class Row {
        final String pkg;
        /** The card activity OK starts, with EXTRA_ITEM. */
        final ComponentName target;
        final String authority;
        final String id;
        final String title;
        final List<Item> items;

        Row(String pkg, ComponentName target, String authority, String id, String title, List<Item> items) {
            this.pkg = pkg;
            this.target = target;
            this.authority = authority;
            this.id = id;
            this.title = title;
            this.items = items;
        }

        /** The same for this row in every read, so focus stays on it when the rows are read again. */
        String key() {
            return pkg + "/" + id;
        }
    }

    private static ExecutorService loader;
    private static volatile boolean broken;

    private HomeShelves() {
    }

    // ---- the guard -------------------------------------------------------------------------------

    /** False once the launcher's own shelf code failed in this process: Home then shows no rows, as when off. */
    static boolean working() {
        return !broken;
    }

    /** Switches the rows off for the rest of the process, with one log line. */
    static void fail(Throwable t) {
        if (broken) return;
        broken = true;
        try {
            Log.w(TAG, "home_shelves switched off for this session: " + t);
        } catch (RuntimeException ignored) {
            // No log on the JVM tests' android.jar.
        }
    }

    /** For tests only. */
    static void reset() {
        broken = false;
    }

    /** One background thread for every read, made on the first one: nothing runs while home_shelves is off. */
    static synchronized ExecutorService loader() {
        if (loader == null) {
            loader = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "home-shelves");
                t.setDaemon(true);
                return t;
            });
        }
        return loader;
    }

    // ---- reading (loader thread only) ------------------------------------------------------------

    static Uri rowsUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("shelves").build();
    }

    /**
     * The rows of the shown cards, in the cards' order, at most {@link #MAX_ROWS} in all. Blocking (binder calls,
     * file reads and decoding): only on the {@link #loader()} thread. pictures holds the last read's pictures by
     * URI and is reused for the same URI, so a read that finds nothing new decodes nothing; afterwards it holds
     * the pictures this read uses. logged keeps a module that fails from logging on every read.
     */
    static List<Row> read(Context ctx, List<Cards.Card> shown, Map<String, Bitmap> pictures, int pictureW,
                          int pictureH, Set<String> logged) {
        PackageManager pm = ctx.getPackageManager();
        ContentResolver cr = ctx.getContentResolver();
        List<Row> rows = new ArrayList<>();
        Map<String, Bitmap> used = new HashMap<>();
        for (Cards.Card card : shown) {
            if (rows.size() >= MAX_ROWS) break;
            String authority = authority(pm, card, logged);
            if (authority == null) continue;
            try {
                rows.addAll(readCard(cr, card, authority, MAX_ROWS - rows.size(), pictures, used, pictureW, pictureH));
            } catch (RuntimeException e) {
                // A SecurityException (the module refuses this launcher), a provider that died or wrote bad columns.
                if (logged.add(card.pkg)) Log.w(TAG, card.pkg + ": " + e);
            }
        }
        pictures.clear();
        pictures.putAll(used);
        return rows;
    }

    /** The provider authority a shown card names, when it is one of the card's own app; else null. */
    private static String authority(PackageManager pm, Cards.Card card, Set<String> logged) {
        try {
            ActivityInfo ai = pm.getActivityInfo(card.component, PackageManager.GET_META_DATA);
            Bundle meta = ai.metaData;
            if (meta == null || !meta.containsKey(META)) return null;   // this card offers no rows: no log
            String authority = meta.getString(META);
            String reason = null;
            if (!validAuthority(authority)) {
                reason = "the " + META + " meta-data is not a provider authority";
            } else {
                ProviderInfo provider = pm.resolveContentProvider(authority, 0);
                if (provider == null) reason = "no provider answers " + authority;
                else if (!card.pkg.equals(provider.packageName)) reason = authority + " belongs to another app";
            }
            if (reason == null) return authority;
            if (logged.add(card.pkg)) Log.w(TAG, card.pkg + ": " + reason);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            if (logged.add(card.pkg)) Log.w(TAG, card.pkg + ": " + e);
        }
        return null;
    }

    private static List<Row> readCard(ContentResolver cr, Cards.Card card, String authority, int room,
                                      Map<String, Bitmap> cache, Map<String, Bitmap> used, int w, int h) {
        Uri base = rowsUri(authority);
        List<String[]> heads = new ArrayList<>();
        try (Cursor c = cr.query(base, null, null, null, null)) {
            if (c == null) return new ArrayList<>();
            int idCol = c.getColumnIndex("id"), titleCol = c.getColumnIndex("title");
            if (idCol < 0 || titleCol < 0) throw new IllegalArgumentException("the rows have no id and title columns");
            Set<String> ids = new HashSet<>();
            int walked = 0;
            while (heads.size() < room && walked++ < 2 * MAX_ROWS && c.moveToNext()) {
                String id = cleanId(text(c, idCol));
                String title = clean(text(c, titleCol), HEADING_MAX);
                if (id == null || title.isEmpty() || !ids.add(id)) continue;
                heads.add(new String[]{id, title});
            }
        }
        List<Row> out = new ArrayList<>();
        for (String[] head : heads) {
            List<Item> items = readItems(cr, base.buildUpon().appendPath(head[0]).build(), authority, cache, used, w, h);
            if (!items.isEmpty()) out.add(new Row(card.pkg, card.component, authority, head[0], head[1], items));
        }
        return out;
    }

    private static List<Item> readItems(ContentResolver cr, Uri uri, String authority, Map<String, Bitmap> cache,
                                        Map<String, Bitmap> used, int w, int h) {
        List<Item> items = new ArrayList<>();
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c == null) return items;
            int idCol = c.getColumnIndex("id"), titleCol = c.getColumnIndex("title");
            int subtitleCol = c.getColumnIndex("subtitle"), progressCol = c.getColumnIndex("progress");
            int imageCol = c.getColumnIndex("image");
            if (idCol < 0 || titleCol < 0) return items;
            Set<String> ids = new HashSet<>();
            int walked = 0;
            while (items.size() < MAX_ITEMS && walked++ < 2 * MAX_ITEMS && c.moveToNext()) {
                String id = cleanId(text(c, idCol));
                String title = clean(text(c, titleCol), TITLE_MAX);
                if (id == null || title.isEmpty() || !ids.add(id)) continue;
                String subtitle = subtitleCol < 0 ? "" : clean(text(c, subtitleCol), SUBTITLE_MAX);
                int progress = progressCol < 0 ? -1 : progress(number(c, progressCol));
                String image = imageCol < 0 ? null : text(c, imageCol);
                if (!imageAllowed(image, authority)) image = null;
                Bitmap picture = null;
                if (image != null) {
                    picture = used.get(image);
                    if (picture == null) picture = cache.get(image);
                    if (picture == null) picture = decode(cr, Uri.parse(image), w, h);
                    if (picture != null) used.put(image, picture);
                }
                items.add(new Item(id, title, subtitle, progress, image, picture));
            }
        }
        return items;
    }

    private static String text(Cursor c, int col) {
        try {
            return c.isNull(col) ? null : c.getString(col);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(Cursor c, int col) {
        try {
            return c.isNull(col) ? -1 : c.getInt(col);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * The picture, decoded to cover a w x h card (CENTER_CROP) and no bigger, or null. RGB_565 unless the picture
     * has transparency: half the memory of ARGB_8888 for a photo.
     */
    static Bitmap decode(ContentResolver cr, Uri uri, int w, int h) {
        byte[] data;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            data = readAtMost(in, IMAGE_MAX_BYTES);
        } catch (IOException | RuntimeException e) {
            return null;   // no picture yet (FileNotFoundException), or the module refused
        }
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > IMAGE_MAX_PIXELS) return null;
            int sample = WallpaperSize.sampleSize(o.outWidth, o.outHeight, Math.max(1, w), Math.max(1, h));
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(o.outWidth / sample, o.outHeight / sample, w, h);
            if (scale != null) {
                o.inScaled = true;
                o.inDensity = scale[0];
                o.inTargetDensity = scale[1];
            }
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- checks (pure Java, unit tested) ---------------------------------------------------------

    /** The whole stream when it is at most max bytes, else null (the rest is not read). */
    static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** A provider authority as a manifest writes one: letters, digits, '.', '_' and '-', with no empty part. */
    static boolean validAuthority(String a) {
        if (a == null || a.isEmpty() || a.length() > AUTHORITY_MAX) return false;
        char prev = '.';
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (!plain(c) && c != '.') return false;
            if (c == '.' && prev == '.') return false;   // a leading dot or ".."
            prev = c;
        }
        return prev != '.';
    }

    /** An id as the module wrote it, or null when it is empty, longer than ID_MAX or holds a control character. */
    static String cleanId(String id) {
        if (id == null || id.isEmpty() || id.length() > ID_MAX) return null;
        for (int i = 0; i < id.length(); i++) {
            if (Character.isISOControl(id.charAt(i))) return null;
        }
        return id;
    }

    /** A heading or a line for the screen: control and format characters are spaces, at most max characters. */
    static String clean(String s, int max) {
        return Cards.cleanLabel(s, "", max);
    }

    /** The bar's share: -1 (no bar) for anything negative, at most 100. */
    static int progress(int value) {
        return value < 0 ? -1 : Math.min(100, value);
    }

    /**
     * True when a picture is a content:// URI of the row's own provider with a plain path: letters, digits, '.',
     * '_', '-' and '/', no empty part and no "..". Nothing else is opened; the launcher has no network.
     */
    static boolean imageAllowed(String image, String authority) {
        if (image == null || image.length() > IMAGE_URI_MAX) return false;
        String prefix = "content://" + authority + "/";
        if (!image.startsWith(prefix) || image.length() == prefix.length()) return false;
        char prev = '/';
        for (int i = prefix.length(); i < image.length(); i++) {
            char c = image.charAt(i);
            if (!plain(c) && c != '.' && c != '/') return false;
            if (c == '/' && prev == '/') return false;
            if (c == '.' && prev == '.') return false;
            prev = c;
        }
        return prev != '/';
    }

    private static boolean plain(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-';
    }

    /** True when two reads show the same thing (same rows, titles, lines, bars and the very same pictures). */
    static boolean same(List<Row> a, List<Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Row x = a.get(i), y = b.get(i);
            if (!x.key().equals(y.key()) || !x.title.equals(y.title) || x.items.size() != y.items.size()) return false;
            for (int j = 0; j < x.items.size(); j++) {
                Item p = x.items.get(j), q = y.items.get(j);
                if (!p.id.equals(q.id) || !p.title.equals(q.title) || !p.subtitle.equals(q.subtitle)
                        || p.progress != q.progress || p.picture != q.picture) return false;
            }
        }
        return true;
    }

    /**
     * How far Home moves up (an offset of 0 or less) while a row has focus: the row's top (rowTop, from the rows'
     * own top) comes to focusY on screen, but Home never moves further than it takes to bring the rows' bottom up
     * to bottomY. At rest the rows' top is at restTop, with only their first heading showing under the apps.
     */
    static int pageOffset(int restTop, int rowTop, int focusY, int rowsHeight, int bottomY) {
        int bringRow = focusY - (restTop + rowTop);
        int lowest = bottomY - (restTop + rowsHeight);
        return Math.min(0, Math.max(bringRow, lowest));
    }

    /** The index to focus in a row of size items: the one remembered for it, kept inside the row. */
    static int clampIndex(int remembered, int size) {
        return size <= 0 ? -1 : Math.max(0, Math.min(remembered, size - 1));
    }
}
