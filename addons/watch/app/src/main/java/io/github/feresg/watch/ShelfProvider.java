package io.github.feresg.watch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watch's rows for the launcher's Home (its home_shelves switch): {@code shelves} lists the rows, {@code shelves/<id>}
 * a row's titles, {@code images/<name>} a card's picture. Only the launcher may read it, signed with the same key, so
 * what someone watches stays private. It answers from the copy on disk at once; when that copy is older than 30
 * minutes it reads Trakt and TMDB again on one thread of its own and tells the launcher, which reads it afresh. No
 * service and no timer: nothing is fetched unless Home asks.
 */
public class ShelfProvider extends ContentProvider {
    /** The only app allowed to read the rows. */
    static final String LAUNCHER = "com.gathin.tvhome";
    static final String[] SHELF_COLUMNS = {"id", "title"};
    static final String[] ITEM_COLUMNS = {"id", "title", "subtitle", "progress", "image"};

    private static final ExecutorService REFRESH = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    /** True when the caller is the launcher signed like this app; the rows are nobody else's business. */
    static boolean allowed(String caller, int signatureCheck) {
        return LAUNCHER.equals(caller) && signatureCheck == PackageManager.SIGNATURE_MATCH;
    }

    private void enforce() {
        Context c = getContext();
        String caller = getCallingPackage();
        int check = caller == null ? PackageManager.SIGNATURE_UNKNOWN_PACKAGE
                : c.getPackageManager().checkSignatures(c.getPackageName(), caller);
        if (!allowed(caller, check)) throw new SecurityException("Watch's rows are for the launcher only");
    }

    static Uri shelvesUri(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".shelves/shelves");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        enforce();
        Context c = getContext();
        Shelves.Snapshot snap = Shelves.load(c);
        if (Shelves.stale(snap.fetchedAt, System.currentTimeMillis())) refresh(c);
        List<String> path = uri.getPathSegments();
        MatrixCursor out;
        if (path.size() == 1 && "shelves".equals(path.get(0))) {
            out = new MatrixCursor(SHELF_COLUMNS);
            for (Shelves.Shelf s : snap.shelves) out.addRow(new Object[]{s.id, s.title});
        } else if (path.size() == 2 && "shelves".equals(path.get(0))) {
            out = new MatrixCursor(ITEM_COLUMNS);
            for (Shelves.Shelf s : snap.shelves) {
                if (!s.id.equals(path.get(1))) continue;
                File dir = Shelves.imageDir(c);
                for (Title t : s.titles) out.addRow(itemRow(t, uri.getAuthority(), dir));
            }
        } else {
            return null;
        }
        out.setNotificationUri(c.getContentResolver(), shelvesUri(c));
        return out;
    }

    /** One card: the title's key (handed back when it is opened), name, line under it, progress and picture. */
    static Object[] itemRow(Title t, String authority, File imageDir) {
        String key = Shelves.imageKey(Shelves.imagePath(t));
        String image = key != null && new File(imageDir, key).isFile() ? "content://" + authority + "/images/" + key : "";
        int progress = t.progress < 0 ? -1 : (int) Math.round(t.progress);
        return new Object[]{t.key(), t.name, t.caption(), progress, image};
    }

    /** Reads the rows again on Watch's own thread, once at a time, then tells the launcher they changed. */
    private static void refresh(Context c) {
        if (!REFRESHING.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        REFRESH.execute(() -> {
            try {
                Config config = Config.load(app);
                Trakt trakt = new Trakt(config, Config.trakt(app));
                List<Shelves.Shelf> shelves = Shelves.fetch(config, trakt, config.homeShelves, LocalDate.now());
                Shelves.cacheImages(app, shelves);
                Shelves.save(app, new Shelves.Snapshot(shelves, System.currentTimeMillis()));
                app.getContentResolver().notifyChange(shelvesUri(app), null);
            } catch (Exception ignored) {
                // The rows on disk stay; Home asks again later.
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforce();
        List<String> path = uri.getPathSegments();
        if (!"r".equals(mode) || path.size() != 2 || !"images".equals(path.get(0)) || !Shelves.validKey(path.get(1))) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(Shelves.imageDir(getContext()), path.get(1));
        if (!f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
