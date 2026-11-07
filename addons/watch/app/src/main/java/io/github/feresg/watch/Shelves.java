package io.github.feresg.watch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The rows Home shows from Watch (Up next, Continue, Trending, New on streaming), kept on disk with their pictures so
 * the launcher reads them without network access. Also the fetch helpers the Watch screen shares. Blocking calls:
 * off the main thread.
 */
final class Shelves {
    static final String UP_NEXT = "up_next";
    static final String CONTINUE = "continue";
    static final String TRENDING = "trending";
    static final String NEW = "new";
    static final String[] ALL = {UP_NEXT, CONTINUE, TRENDING, NEW};
    /** Titles per row, on the Watch screen and on Home. */
    static final int ROW_MAX = 20;
    /** Home's rows are read again once they are older than this, when Home asks for them. */
    static final long FRESH_MS = 30 * 60 * 1000L;
    static final String FILE = "shelves.json";
    static final String IMAGES = "shelf-images";
    private static final int IMAGE_MAX_BYTES = 1024 * 1024;

    /** One Home row: an id the launcher hands back, its heading and its titles. */
    static final class Shelf {
        final String id;
        final String title;
        final List<Title> titles;

        Shelf(String id, String title, List<Title> titles) {
            this.id = id;
            this.title = title;
            this.titles = titles;
        }
    }

    /** The rows and when they were read, in ms since the epoch (0 for never). */
    static final class Snapshot {
        final List<Shelf> shelves;
        final long fetchedAt;

        Snapshot(List<Shelf> shelves, long fetchedAt) {
            this.shelves = shelves;
            this.fetchedAt = fetchedAt;
        }

        /** The title with this key in any row, or null. */
        Title find(String key) {
            for (Shelf s : shelves) for (Title t : s.titles) if (t.key().equals(key)) return t;
            return null;
        }
    }

    private Shelves() {
    }

    // ---- Shared with the Watch screen

    /** Posters and overviews for Trakt's titles, read from TMDB four at a time. */
    static List<Title> complete(Config c, Tmdb tmdb, List<Title> titles) {
        if (!c.hasTmdb() || titles.isEmpty()) return titles;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (Title t : titles) jobs.add(pool.submit(() -> tmdb.complete(t, false)));
            for (Future<?> f : jobs) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // That title stays without a poster.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return titles;
    }

    /** Each title once, at most ROW_MAX. */
    static List<Title> dedupe(List<Title> titles) {
        Set<String> seen = new LinkedHashSet<>();
        List<Title> out = new ArrayList<>();
        for (Title t : titles) {
            if (seen.add(t.key())) out.add(t);
            if (out.size() == ROW_MAX) break;
        }
        return out;
    }

    // ---- Home's rows

    /** Reads the rows in {@code wanted} that the keys allow: the Trakt ones when signed in, the TMDB ones with a key. */
    static List<Shelf> fetch(Config c, Trakt trakt, List<String> wanted, LocalDate today) {
        Tmdb tmdb = new Tmdb(c);
        List<Shelf> out = new ArrayList<>();
        for (String id : wanted) {
            try {
                List<Title> titles;
                String heading;
                switch (id) {
                    case UP_NEXT:
                        if (!c.hasTrakt() || !trakt.signedIn()) continue;
                        heading = "Up next";
                        titles = complete(c, tmdb, trakt.upNext());
                        break;
                    case CONTINUE:
                        if (!c.hasTrakt() || !trakt.signedIn()) continue;
                        heading = "Continue watching";
                        titles = complete(c, tmdb, trakt.paused());
                        break;
                    case TRENDING:
                        if (!c.hasTmdb()) continue;
                        heading = "Trending this week";
                        titles = tmdb.list(tmdb.trending(), null);
                        break;
                    case NEW:
                        // One row per home_regions country, each a row of its own on Home.
                        if (c.hasTmdb()) for (String region : c.homeRegions) addNew(out, tmdb, region, today);
                        continue;
                    default:
                        continue;
                }
                List<Title> unique = dedupe(titles);
                if (!unique.isEmpty()) out.add(new Shelf(id, heading, unique));
            } catch (JSONException e) {
                // That row is left out this time; the others still show.
            }
        }
        return out;
    }

    /** "New on streaming" for one country (its own productions where its language isn't English), id new_<code>. */
    private static void addNew(List<Shelf> out, Tmdb tmdb, String region, LocalDate today) {
        try {
            String local = Tmdb.localAdjective(region);
            String heading = (local.isEmpty() ? "New on streaming" : "New " + local + "films and series")
                    + " · " + MainActivity.country(region);
            List<Title> unique = dedupe(interleave(tmdb.list(tmdb.newMovies(region, today), "movie"),
                    tmdb.list(tmdb.newSeries(region, today), "tv")));
            if (!unique.isEmpty()) out.add(new Shelf(newId(region), heading, unique));
        } catch (JSONException e) {
            // That country's row is left out this time.
        }
    }

    static String newId(String region) {
        return NEW + "_" + region.toLowerCase(java.util.Locale.ROOT);
    }

    /** Films and series taken in turn, so one row shows both. */
    static List<Title> interleave(List<Title> a, List<Title> b) {
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    /** True when rows read at {@code fetchedAt} should be read again: older than FRESH_MS, never read, or from the future. */
    static boolean stale(long fetchedAt, long now) {
        return fetchedAt <= 0 || fetchedAt > now || now - fetchedAt > FRESH_MS;
    }

    /** The picture a Home card shows: the backdrop, which suits a wide card, else the poster. A TMDB path or "". */
    static String imagePath(Title t) {
        return !t.backdrop.isEmpty() ? t.backdrop : t.poster;
    }

    /** The cache file name for a TMDB path ("/abc123.jpg" to "abc123.jpg"), or null for anything else. */
    static String imageKey(String path) {
        if (path == null || !path.matches("/[A-Za-z0-9_-]{1,64}\\.(jpg|png)")) return null;
        return path.substring(1);
    }

    /** True for a file name imageKey could have made: the only names the provider serves. */
    static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,64}\\.(jpg|png)");
    }

    // ---- On disk

    static String toJson(Snapshot s) throws JSONException {
        JSONArray rows = new JSONArray();
        for (Shelf shelf : s.shelves) {
            JSONArray titles = new JSONArray();
            for (Title t : shelf.titles) titles.put(new JSONObject(t.toJson()));
            rows.put(new JSONObject().put("id", shelf.id).put("title", shelf.title).put("titles", titles));
        }
        return new JSONObject().put("fetchedAt", s.fetchedAt).put("shelves", rows).toString();
    }

    static Snapshot fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray rows = o.getJSONArray("shelves");
        List<Shelf> shelves = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            JSONArray a = r.getJSONArray("titles");
            List<Title> titles = new ArrayList<>();
            for (int j = 0; j < a.length(); j++) titles.add(Title.fromJson(a.getJSONObject(j).toString()));
            shelves.add(new Shelf(r.getString("id"), r.getString("title"), titles));
        }
        return new Snapshot(shelves, o.optLong("fetchedAt", 0));
    }

    /** The rows on disk, or none read yet. */
    static Snapshot load(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        try {
            return fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException e) {
            return new Snapshot(new ArrayList<>(), 0);
        }
    }

    static void save(Context c, Snapshot s) throws IOException, JSONException {
        File f = new File(c.getFilesDir(), FILE);
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        Files.write(tmp.toPath(), toJson(s).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) throw new IOException("could not replace " + f);
    }

    /**
     * The rows on disk no longer match the keys or the Trakt sign-in (new keys pushed, signed in): mark them old,
     * keeping them on screen, and tell the launcher, whose next read makes the provider fetch them again.
     */
    static void invalidate(Context c) {
        Snapshot old = load(c);
        try {
            save(c, new Snapshot(old.shelves, 0));
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            new File(c.getFilesDir(), FILE).delete();
        }
        c.getContentResolver().notifyChange(ShelfProvider.shelvesUri(c), null);
    }

    static File imageDir(Context c) {
        return new File(c.getCacheDir(), IMAGES);
    }

    /**
     * Downloads each row's picture (w300, enough for a Home card) unless it is already there, then deletes the
     * pictures no row uses any more. Failures leave that card without a picture.
     */
    static void cacheImages(Context c, List<Shelf> shelves) {
        File dir = imageDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        Set<String> used = new HashSet<>();
        for (Shelf s : shelves) {
            for (Title t : s.titles) {
                String key = imageKey(imagePath(t));
                if (key == null) continue;
                used.add(key);
                File f = new File(dir, key);
                if (!f.isFile()) download(Tmdb.image(imagePath(t), "w300"), f);
            }
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (String name : unused(names(files), used)) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, name).delete();
        }
    }

    private static List<String> names(File[] files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    /** The cached file names no row uses any more. */
    static List<String> unused(List<String> cached, Set<String> used) {
        List<String> out = new ArrayList<>();
        for (String name : cached) if (!used.contains(name)) out.add(name);
        return out;
    }

    private static void download(String url, File to) {
        if (url.isEmpty()) return;
        File tmp = new File(to.getPath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > IMAGE_MAX_BYTES) throw new IOException("picture too large");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(to)) throw new IOException("could not keep " + to);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
