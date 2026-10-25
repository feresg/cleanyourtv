package io.github.feresg.watch;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Trakt: the device sign-in (a code you enter at trakt.tv/activate) and three read-only lists, Up next, paused
 * titles and the watchlist. Nothing is written to Trakt; Umbrella scrobbles what you play. Blocking: call it off
 * the main thread.
 */
final class Trakt {
    static final String API = "https://api.trakt.tv";
    static final String REDIRECT = "urn:ietf:wg:oauth:2.0:oob";
    /** Series looked at for Up next, the most recently watched first. */
    static final int UP_NEXT_SHOWS = 20;
    private static final long REFRESH_BEFORE_MS = 60 * 60 * 1000L;

    static final class DeviceCode {
        final String deviceCode;
        final String userCode;
        final String url;
        final int expiresIn;
        final int interval;

        DeviceCode(String deviceCode, String userCode, String url, int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.url = url;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    static final class Tokens {
        final String access;
        final String refresh;
        /** When the access token stops working, in ms since the epoch. */
        final long expiresAt;

        Tokens(String access, String refresh, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.expiresAt = expiresAt;
        }
    }

    /** What a poll of the device token says. */
    enum Poll { SIGNED_IN, WAITING, SLOW_DOWN, EXPIRED, DENIED, FAILED }

    private final Config config;
    private final SharedPreferences store;

    Trakt(Config config, SharedPreferences store) {
        this.config = config;
        this.store = store;
    }

    boolean signedIn() {
        return !store.getString("access", "").isEmpty();
    }

    void signOut() {
        store.edit().clear().apply();
    }

    // ---- Sign-in

    DeviceCode deviceCode() throws JSONException {
        Http.Reply r = Http.post(API + "/oauth/device/code", headers(false),
                new JSONObject().put("client_id", config.traktId).toString());
        if (!r.ok()) throw new JSONException("Trakt answered " + r.code);
        return parseDeviceCode(r.body);
    }

    Poll poll(DeviceCode code) throws JSONException {
        Http.Reply r = Http.post(API + "/oauth/device/token", headers(false), new JSONObject()
                .put("code", code.deviceCode).put("client_id", config.traktId)
                .put("client_secret", config.traktSecret).toString());
        Poll p = poll(r.code);
        if (p == Poll.SIGNED_IN) save(parseTokens(r.body, System.currentTimeMillis()));
        return p;
    }

    /** The device token answer by status code, as Trakt documents them. */
    static Poll poll(int code) {
        switch (code) {
            case 200: return Poll.SIGNED_IN;
            case 400: return Poll.WAITING;
            case 429: return Poll.SLOW_DOWN;
            case 409:
            case 410: return Poll.EXPIRED;
            case 418: return Poll.DENIED;
            default: return Poll.FAILED;
        }
    }

    private void save(Tokens t) {
        store.edit().putString("access", t.access).putString("refresh", t.refresh).putLong("expires", t.expiresAt).apply();
    }

    /** A fresh access token when this one ends within the hour (or has been refused). False when signed out. */
    private boolean refresh(boolean force) {
        String refresh = store.getString("refresh", "");
        if (refresh.isEmpty()) return false;
        if (!force && store.getLong("expires", 0) - System.currentTimeMillis() > REFRESH_BEFORE_MS) return true;
        try {
            Http.Reply r = Http.post(API + "/oauth/token", headers(false), new JSONObject()
                    .put("refresh_token", refresh).put("client_id", config.traktId)
                    .put("client_secret", config.traktSecret).put("redirect_uri", REDIRECT)
                    .put("grant_type", "refresh_token").toString());
            if (r.ok()) {
                save(parseTokens(r.body, System.currentTimeMillis()));
                return true;
            }
            if (r.code == 400 || r.code == 401) signOut();
        } catch (JSONException ignored) {
            // Keep the old token and try again next time.
        }
        return signedIn();
    }

    private Map<String, String> headers(boolean auth) {
        Map<String, String> h = new HashMap<>();
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", config.traktId);
        if (auth) h.put("Authorization", "Bearer " + store.getString("access", ""));
        return h;
    }

    /** A signed-in GET; refreshes the token once if Trakt refuses it. */
    private String get(String path) throws JSONException {
        if (!refresh(false)) throw new JSONException("signed out");
        Http.Reply r = Http.get(API + path, headers(true));
        if (r.code == 401 && refresh(true)) r = Http.get(API + path, headers(true));
        if (r.code == 401) throw new JSONException("signed out");
        if (!r.ok()) throw new JSONException("Trakt answered " + r.code);
        return r.body;
    }

    // ---- Lists

    /**
     * The next episode of each series you are watching, most recent first: the series you watched last, less the
     * ones hidden from progress on Trakt and the ones with nothing left to watch.
     */
    List<Title> upNext() throws JSONException {
        List<Title> shows = watchedShows(get("/sync/watched/shows?extended=noseasons"));
        Set<Long> hidden;
        try {
            hidden = hidden(get("/users/hidden/progress_watched?type=show&limit=100"));
        } catch (JSONException e) {
            hidden = Collections.emptySet();
        }
        List<Title> look = new ArrayList<>();
        for (Title s : shows) {
            if (!hidden.contains(s.trakt)) look.add(s);
            if (look.size() == UP_NEXT_SHOWS) break;
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Title>> next = new ArrayList<>();
            for (Title s : look) {
                next.add(pool.submit(() -> nextEpisode(s,
                        get("/shows/" + s.trakt + "/progress/watched?hidden=false&specials=false&count_specials=false&extended=full"))));
            }
            List<Title> out = new ArrayList<>();
            for (Future<Title> f : next) {
                try {
                    Title t = f.get();
                    if (t != null) out.add(t);
                } catch (Exception ignored) {
                    // One series that can't be read leaves the row.
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    List<Title> paused() throws JSONException {
        return playback(get("/sync/playback?limit=30"));
    }

    /** "movies" or "shows", the last added first. */
    List<Title> watchlist(String type) throws JSONException {
        return parseWatchlist(get("/sync/watchlist/" + type + "/added/desc?limit=40"));
    }

    // ---- Parsing, as Trakt's API documents the answers

    static DeviceCode parseDeviceCode(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        return new DeviceCode(o.getString("device_code"), o.getString("user_code"),
                o.optString("verification_url", "https://trakt.tv/activate"), o.optInt("expires_in", 600),
                Math.max(1, o.optInt("interval", 5)));
    }

    static Tokens parseTokens(String body, long nowMs) throws JSONException {
        JSONObject o = new JSONObject(body);
        long created = o.optLong("created_at", nowMs / 1000);
        return new Tokens(o.getString("access_token"), o.optString("refresh_token"),
                (created + o.optLong("expires_in", 86400)) * 1000L);
    }

    /** The series in /sync/watched/shows, most recently watched first. */
    static List<Title> watchedShows(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
        // ISO 8601 in UTC sorts as text.
        Collections.sort(items, (x, y) -> y.optString("last_watched_at").compareTo(x.optString("last_watched_at")));
        List<Title> out = new ArrayList<>();
        for (JSONObject o : items) {
            Title t = show(o.optJSONObject("show"));
            if (t != null) out.add(t);
        }
        return out;
    }

    /** The series' next episode from /shows/{id}/progress/watched, or null when there is none. */
    static Title nextEpisode(Title show, String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        JSONObject e = o.optJSONObject("next_episode");
        if (e == null) return null;
        return episode(show, e);
    }

    /** Hidden series' Trakt ids from /users/hidden/progress_watched. */
    static Set<Long> hidden(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONObject show = o == null ? null : o.optJSONObject("show");
            JSONObject ids = show == null ? null : show.optJSONObject("ids");
            if (ids != null && ids.optLong("trakt") > 0) out.add(ids.optLong("trakt"));
        }
        return out;
    }

    /** Paused films and episodes from /sync/playback, as Trakt orders them (the last paused first). */
    static List<Title> playback(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Title t = null;
            if ("movie".equals(o.optString("type"))) {
                t = movie(o.optJSONObject("movie"));
            } else if ("episode".equals(o.optString("type"))) {
                Title show = show(o.optJSONObject("show"));
                JSONObject e = o.optJSONObject("episode");
                if (show != null && e != null) t = episode(show, e);
            }
            if (t == null) continue;
            t.progress = Math.max(0, Math.min(100, o.optDouble("progress", 0)));
            out.add(t);
        }
        return out;
    }

    /** Films and series from /sync/watchlist, as listed. */
    static List<Title> parseWatchlist(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        List<Title> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Title t = "movie".equals(o.optString("type")) ? movie(o.optJSONObject("movie"))
                    : "show".equals(o.optString("type")) ? show(o.optJSONObject("show")) : null;
            if (t != null) out.add(t);
        }
        return out;
    }

    private static Title movie(JSONObject m) {
        return m == null ? null : ids(new Title(Title.Kind.MOVIE), m);
    }

    private static Title show(JSONObject s) {
        return s == null ? null : ids(new Title(Title.Kind.SHOW), s);
    }

    /** An episode carries its series' name and ids: that is what Umbrella plays an episode by. */
    private static Title episode(Title show, JSONObject e) {
        Title t = new Title(Title.Kind.EPISODE);
        t.name = show.name;
        t.year = show.year;
        t.tmdb = show.tmdb;
        t.trakt = show.trakt;
        t.imdb = show.imdb;
        t.tvdb = show.tvdb;
        t.season = e.optInt("season");
        t.episode = e.optInt("number");
        t.episodeTitle = e.isNull("title") ? "" : e.optString("title");
        String aired = e.isNull("first_aired") ? "" : e.optString("first_aired");
        t.premiered = aired.length() >= 10 ? aired.substring(0, 10) : "";
        if (!e.isNull("overview")) t.overview = e.optString("overview");
        return t;
    }

    private static Title ids(Title t, JSONObject o) {
        t.name = o.optString("title");
        t.year = o.optInt("year");
        JSONObject ids = o.optJSONObject("ids");
        if (ids != null) {
            t.trakt = ids.optLong("trakt");
            t.tmdb = ids.optLong("tmdb");
            t.tvdb = ids.optLong("tvdb");
            t.imdb = ids.isNull("imdb") ? "" : ids.optString("imdb");
        }
        return t;
    }
}
