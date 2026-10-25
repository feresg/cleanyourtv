package io.github.feresg.watch;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Addresses into the Umbrella add-on for Kodi, with the parameters its router reads
 * (resources/lib/modules/router.py): play_Item scrapes sources for a film or an episode and plays the one picked,
 * the same address Umbrella's own lists use; seasons and episodes open its pages for a series.
 */
final class Umbrella {
    static final String BASE = "plugin://plugin.video.umbrella/";

    private Umbrella() {
    }

    static String play(Title t) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", "play_Item");
        if (t.kind == Title.Kind.EPISODE) {
            p.put("title", t.episodeTitle.isEmpty() ? t.episodeCode() : t.episodeTitle);
            p.put("year", year(t));
            p.put("imdb", t.imdb);
            p.put("tmdb", id(t.tmdb));
            p.put("tvdb", id(t.tvdb));
            p.put("season", String.valueOf(t.season));
            p.put("episode", String.valueOf(t.episode));
            p.put("tvshowtitle", t.name);
            p.put("premiered", t.premiered);
        } else {
            p.put("title", t.name);
            p.put("year", year(t));
            p.put("imdb", t.imdb);
            p.put("tmdb", id(t.tmdb));
        }
        return url(p);
    }

    static String seasons(Title t) {
        Map<String, String> p = show("seasons", t);
        return url(p);
    }

    /** The episodes of the title's season. */
    static String episodes(Title t) {
        Map<String, String> p = show("episodes", t);
        p.put("season", String.valueOf(t.season));
        return url(p);
    }

    private static Map<String, String> show(String action, Title t) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", action);
        p.put("tvshowtitle", t.name);
        p.put("year", year(t));
        p.put("imdb", t.imdb);
        p.put("tmdb", id(t.tmdb));
        p.put("tvdb", id(t.tvdb));
        return p;
    }

    private static String year(Title t) {
        return t.year > 0 ? String.valueOf(t.year) : "";
    }

    private static String id(long id) {
        return id > 0 ? String.valueOf(id) : "";
    }

    /** Encoded as Python's urllib quote_plus does, which Umbrella's parse_qsl reads back. */
    static String url(Map<String, String> params) {
        StringBuilder b = new StringBuilder(BASE).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) b.append('&');
            first = false;
            b.append(e.getKey()).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        return b.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
