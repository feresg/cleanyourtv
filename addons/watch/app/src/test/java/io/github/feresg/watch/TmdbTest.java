package io.github.feresg.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/** Against the shapes TMDB's API documents for trending, discover and details with external ids. */
public class TmdbTest {
    private static Config config(String token) {
        return new Config(token, "", "", 8080, "kodi", "", Config.regions("US,UK,fr"), "en-US");
    }

    @Test
    public void trendingKeepsFilmsAndSeriesWithPosters() throws Exception {
        List<Title> t = Tmdb.parseList("{\"page\":1,\"results\":["
                + "{\"id\":1022789,\"media_type\":\"movie\",\"title\":\"Inside Out 2\",\"release_date\":\"2024-06-11\","
                + "\"poster_path\":\"/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg\",\"backdrop_path\":\"/p5ozvmdgsmbWe0H8Xk7Rc8SCwAB.jpg\","
                + "\"overview\":\"Teenager Riley's mind headquarters…\",\"vote_average\":7.6},"
                + "{\"id\":94997,\"media_type\":\"tv\",\"name\":\"House of the Dragon\",\"first_air_date\":\"2022-08-21\","
                + "\"poster_path\":\"/t9XkeE7HzOsdQcDDDapDYh8Rrmt.jpg\",\"vote_average\":8.4},"
                + "{\"id\":976573,\"media_type\":\"person\",\"name\":\"Someone\",\"profile_path\":\"/x.jpg\"},"
                + "{\"id\":5,\"media_type\":\"movie\",\"title\":\"No poster\",\"poster_path\":null}"
                + "]}", null);
        assertEquals(2, t.size());
        assertEquals(Title.Kind.MOVIE, t.get(0).kind);
        assertEquals(2024, t.get(0).year);
        assertEquals(Title.Kind.SHOW, t.get(1).kind);
        assertEquals("House of the Dragon", t.get(1).name);
        assertEquals(2022, t.get(1).year);
        assertEquals("https://image.tmdb.org/t/p/w185/t9XkeE7HzOsdQcDDDapDYh8Rrmt.jpg", Tmdb.image(t.get(1).poster, "w185"));
    }

    @Test
    public void discoverListsTakeTheirTypeFromTheRequest() throws Exception {
        List<Title> t = Tmdb.parseList("{\"results\":[{\"id\":1396,\"name\":\"Breaking Bad\",\"first_air_date\":\"2008-01-20\","
                + "\"poster_path\":\"/ggFHVNu6YYI5L9pCfOacjizRGt.jpg\"}]}", "tv");
        assertEquals(Title.Kind.SHOW, t.get(0).kind);
        assertEquals("tv:1396", t.get(0).key());
    }

    @Test
    public void detailsFillTheIdsUmbrellaPlaysBy() throws Exception {
        Title t = new Title(Title.Kind.SHOW);
        t.tmdb = 1396;
        Tmdb.fill(t, new JSONObject("{\"id\":1396,\"name\":\"Breaking Bad\",\"first_air_date\":\"2008-01-20\","
                + "\"overview\":\"A chemistry teacher…\",\"poster_path\":\"/p.jpg\",\"backdrop_path\":\"/b.jpg\",\"vote_average\":8.9,"
                + "\"external_ids\":{\"imdb_id\":\"tt0903747\",\"tvdb_id\":81189,\"facebook_id\":null}}"));
        assertEquals("Breaking Bad", t.name);
        assertEquals("tt0903747", t.imdb);
        assertEquals(81189, t.tvdb);
        assertEquals(8.9, t.rating, 0.001);
        Title m = new Title(Title.Kind.MOVIE);
        Tmdb.fill(m, new JSONObject("{\"id\":272,\"title\":\"Batman Begins\",\"imdb_id\":\"tt0372784\",\"release_date\":\"\"}"));
        assertEquals("tt0372784", m.imdb);
        assertEquals(0, m.year);
    }

    @Test
    public void addressesPerRegion() {
        Tmdb tmdb = new Tmdb(config("abc"));
        assertTrue(tmdb.popularStreaming("movie", "FR").contains("/discover/movie?")
                && tmdb.popularStreaming("movie", "FR").contains("watch_region=FR&with_watch_monetization_types=flatrate"));
        String n = tmdb.newMovies("GB", LocalDate.of(2026, 10, 25));
        assertTrue(n, n.contains("region=GB&with_release_type=4&release_date.gte=2026-09-10&release_date.lte=2026-10-25"));
        String s = tmdb.newSeries("US", LocalDate.of(2026, 10, 25));
        assertTrue(s, s.contains("first_air_date.gte=2026-08-26&first_air_date.lte=2026-10-25"));
    }

    @Test
    public void regions() {
        assertEquals(java.util.Arrays.asList("US", "GB", "FR"), Config.regions("US, uk ,FR,US"));
        assertEquals(Config.DEFAULT_REGIONS, Config.regions(""));
        assertEquals(Collections.singletonList("DE"), Config.regions("de,xyz"));
        assertEquals("UK", MainActivity.country("GB"));
        assertEquals("France", MainActivity.country("FR"));
    }
}
