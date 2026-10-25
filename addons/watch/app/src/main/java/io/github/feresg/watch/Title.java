package io.github.feresg.watch;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A film, a series or one episode of it, with whatever ids are known. Trakt gives Trakt, TMDB, IMDb and TVDB ids;
 * TMDB lists give the TMDB id, and the rest is looked up when the title is opened.
 */
final class Title {
    enum Kind { MOVIE, SHOW, EPISODE }

    Kind kind;
    /** The film's or the series' name. */
    String name = "";
    int year;
    long tmdb;
    /** Trakt's own id, for the series' progress; 0 for titles from TMDB. */
    long trakt;
    String imdb = "";
    long tvdb;
    /** For an episode: its number, its own title and its first air date (yyyy-mm-dd). */
    int season;
    int episode;
    String episodeTitle = "";
    String premiered = "";
    String poster = "";
    String backdrop = "";
    String overview = "";
    double rating;
    /** Watched so far, 0 to 100, for a paused title; negative when not paused. */
    double progress = -1;

    Title(Kind kind) {
        this.kind = kind;
    }

    boolean isShow() {
        return kind != Kind.MOVIE;
    }

    /** The TMDB path segment for the film or the series: "movie" or "tv". */
    String tmdbType() {
        return kind == Kind.MOVIE ? "movie" : "tv";
    }

    /** "S02E05", for an episode. */
    String episodeCode() {
        return String.format(java.util.Locale.ROOT, "S%02dE%02d", season, episode);
    }

    /** The line under the poster: the year, or the episode for Up next. */
    String caption() {
        if (kind == Kind.EPISODE) return episodeCode() + (episodeTitle.isEmpty() ? "" : " · " + episodeTitle);
        return year > 0 ? String.valueOf(year) : "";
    }

    /** A key for de-duplicating a row and caching posters. */
    String key() {
        return tmdbType() + ":" + (tmdb > 0 ? String.valueOf(tmdb) : name + year) + (kind == Kind.EPISODE ? ":" + episodeCode() : "");
    }

    String toJson() {
        try {
            return new JSONObject().put("kind", kind.name()).put("name", name).put("year", year).put("tmdb", tmdb)
                    .put("trakt", trakt).put("imdb", imdb).put("tvdb", tvdb).put("season", season).put("episode", episode)
                    .put("episodeTitle", episodeTitle).put("premiered", premiered).put("poster", poster)
                    .put("backdrop", backdrop).put("overview", overview).put("rating", rating)
                    .put("progress", progress).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Title fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Title t = new Title(Kind.valueOf(o.getString("kind")));
        t.name = o.optString("name");
        t.year = o.optInt("year");
        t.tmdb = o.optLong("tmdb");
        t.trakt = o.optLong("trakt");
        t.imdb = o.optString("imdb");
        t.tvdb = o.optLong("tvdb");
        t.season = o.optInt("season");
        t.episode = o.optInt("episode");
        t.episodeTitle = o.optString("episodeTitle");
        t.premiered = o.optString("premiered");
        t.poster = o.optString("poster");
        t.backdrop = o.optString("backdrop");
        t.overview = o.optString("overview");
        t.rating = o.optDouble("rating", 0);
        t.progress = o.optDouble("progress", -1);
        return t;
    }
}
