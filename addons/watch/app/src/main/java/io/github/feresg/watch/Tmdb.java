package io.github.feresg.watch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMDB: what is trending, what is popular on subscription streaming in a country, and what is new there, plus
 * the posters and the IMDb and TVDB ids Umbrella plays by. Blocking: call it off the main thread.
 */
final class Tmdb {
    static final String API = "https://api.themoviedb.org/3";
    static final String IMAGES = "https://image.tmdb.org/t/p/";
    /** How far back "new" goes. */
    static final int NEW_MOVIE_DAYS = 45;
    static final int NEW_SERIES_DAYS = 60;

    private final Config config;
    /** Details already read this session, by "movie:123" or "tv:456". */
    private static final Map<String, JSONObject> DETAILS = Collections.synchronizedMap(new HashMap<>());

    Tmdb(Config config) {
        this.config = config;
    }

    // ---- Addresses

    String trending() {
        return API + "/trending/all/week?language=" + enc(config.language);
    }

    /** Popular on subscription streaming (flatrate) in a country, for "movie" or "tv". */
    String popularStreaming(String type, String region) {
        return API + "/discover/" + type + "?include_adult=false&sort_by=popularity.desc&watch_region=" + enc(region)
                + "&with_watch_monetization_types=flatrate&language=" + enc(config.language);
    }

    /** Films released for digital viewing in a country over the last weeks, most popular first. */
    String newMovies(String region, LocalDate today) {
        return API + "/discover/movie?include_adult=false&sort_by=popularity.desc&region=" + enc(region)
                + "&with_release_type=4&release_date.gte=" + today.minusDays(NEW_MOVIE_DAYS)
                + "&release_date.lte=" + today + "&language=" + enc(config.language);
    }

    /** Series that started over the last weeks and stream on a subscription in a country, most popular first. */
    String newSeries(String region, LocalDate today) {
        return API + "/discover/tv?include_adult=false&sort_by=popularity.desc&watch_region=" + enc(region)
                + "&with_watch_monetization_types=flatrate&first_air_date.gte=" + today.minusDays(NEW_SERIES_DAYS)
                + "&first_air_date.lte=" + today + "&language=" + enc(config.language);
    }

    /** One list; type is null for the trending list, which says each item's type itself. */
    List<Title> list(String url, String type) throws JSONException {
        return parseList(call(url), type);
    }

    /**
     * Fills in what a Trakt title lacks (poster, backdrop, overview, rating) and, with ids true, the IMDb and TVDB
     * ids. Read once per title and session.
     */
    void complete(Title t, boolean ids) {
        if (t.tmdb <= 0) return;
        String key = t.tmdbType() + ":" + t.tmdb;
        JSONObject d = DETAILS.get(key);
        try {
            if (d == null || (ids && !d.has("external_ids"))) {
                d = new JSONObject(call(API + "/" + t.tmdbType() + "/" + t.tmdb + "?language=" + enc(config.language)
                        + (ids ? "&append_to_response=external_ids" : "")));
                DETAILS.put(key, d);
            }
            fill(t, d);
        } catch (JSONException ignored) {
            // The title stays as it is: shown without a poster, played by what is known.
        }
    }

    private String call(String url) throws JSONException {
        Map<String, String> h = new HashMap<>();
        String u = url;
        // A v4 read access token is a long JWT and goes in the header; a v3 key goes in the address.
        if (config.tmdbToken.length() > 40) h.put("Authorization", "Bearer " + config.tmdbToken);
        else u = url + (url.contains("?") ? "&" : "?") + "api_key=" + enc(config.tmdbToken);
        Http.Reply r = Http.get(u, h);
        if (r.code == 401) throw new JSONException("TMDB refused the key");
        if (!r.ok()) throw new JSONException("TMDB answered " + r.code);
        return r.body;
    }

    static String image(String path, String size) {
        return path == null || path.isEmpty() ? "" : IMAGES + size + path;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Parsing, as TMDB's API documents the answers

    /** The results of a trending or discover answer; people and titles without a poster are left out. */
    static List<Title> parseList(String body, String type) throws JSONException {
        JSONArray a = new JSONObject(body).optJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String kind = type != null ? type : o.optString("media_type");
            if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
            Title t = new Title("movie".equals(kind) ? Title.Kind.MOVIE : Title.Kind.SHOW);
            t.tmdb = o.optLong("id");
            fill(t, o);
            if (t.tmdb > 0 && !t.poster.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Takes what the title doesn't have yet from a TMDB object (a list result or the details). */
    static void fill(Title t, JSONObject o) {
        if (t.name.isEmpty()) t.name = text(o, t.kind == Title.Kind.MOVIE ? "title" : "name");
        if (t.year == 0) {
            String date = text(o, t.kind == Title.Kind.MOVIE ? "release_date" : "first_air_date");
            if (date.length() >= 4 && date.substring(0, 4).matches("\\d{4}")) t.year = Integer.parseInt(date.substring(0, 4));
        }
        if (t.poster.isEmpty()) t.poster = text(o, "poster_path");
        if (t.backdrop.isEmpty()) t.backdrop = text(o, "backdrop_path");
        if (t.overview.isEmpty()) t.overview = text(o, "overview");
        if (t.rating == 0) t.rating = o.optDouble("vote_average", 0);
        JSONObject ext = o.optJSONObject("external_ids");
        if (ext != null) {
            if (t.imdb.isEmpty()) t.imdb = text(ext, "imdb_id");
            if (t.tvdb == 0) t.tvdb = ext.optLong("tvdb_id", 0);
        }
        if (t.kind == Title.Kind.MOVIE && t.imdb.isEmpty()) t.imdb = text(o, "imdb_id");
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }
}
