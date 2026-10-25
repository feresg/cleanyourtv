package io.github.feresg.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/** Against the shapes Trakt's API documents for the device sign-in, watched shows, progress, playback and watchlist. */
public class TraktTest {
    static final String WATCHED = "["
            + "{\"plays\":56,\"last_watched_at\":\"2026-09-01T09:10:11.000Z\",\"last_updated_at\":\"2026-09-01T09:10:11.000Z\","
            + "\"show\":{\"title\":\"Breaking Bad\",\"year\":2008,\"ids\":{\"trakt\":1388,\"slug\":\"breaking-bad\",\"tvdb\":81189,\"imdb\":\"tt0903747\",\"tmdb\":1396}}},"
            + "{\"plays\":23,\"last_watched_at\":\"2026-10-20T20:00:00.000Z\","
            + "\"show\":{\"title\":\"Parks and Recreation\",\"year\":2009,\"ids\":{\"trakt\":8546,\"slug\":\"parks-and-recreation\",\"tvdb\":84912,\"imdb\":\"tt1266020\",\"tmdb\":8592}}}"
            + "]";

    @Test
    public void deviceCodeAndTokens() throws Exception {
        Trakt.DeviceCode c = Trakt.parseDeviceCode("{\"device_code\":\"d9c126a7706328d808914cfd1e40274b6e009f684b1aca271b8b2ab20eb8ab6\","
                + "\"user_code\":\"5055CC52\",\"verification_url\":\"https://trakt.tv/activate\",\"expires_in\":600,\"interval\":5}");
        assertEquals("5055CC52", c.userCode);
        assertEquals("https://trakt.tv/activate", c.url);
        assertEquals(5, c.interval);
        Trakt.Tokens t = Trakt.parseTokens("{\"access_token\":\"dbaf9757982a9e738f05d249b7b5b4a266b3a139049317c4909f2f263572c781\","
                + "\"token_type\":\"bearer\",\"expires_in\":86400,\"refresh_token\":\"76ba4c5c75c96f6087f58a4de10be6c00b29ea1ddc3b2022ee2016d1363e3a7c\","
                + "\"scope\":\"public\",\"created_at\":1487889741}", 0);
        assertEquals((1487889741L + 86400) * 1000, t.expiresAt);
        assertTrue(t.refresh.startsWith("76ba"));
    }

    @Test
    public void pollAnswersByStatusCode() {
        assertEquals(Trakt.Poll.SIGNED_IN, Trakt.poll(200));
        assertEquals(Trakt.Poll.WAITING, Trakt.poll(400));
        assertEquals(Trakt.Poll.SLOW_DOWN, Trakt.poll(429));
        assertEquals(Trakt.Poll.EXPIRED, Trakt.poll(410));
        assertEquals(Trakt.Poll.DENIED, Trakt.poll(418));
        assertEquals(Trakt.Poll.FAILED, Trakt.poll(404));
    }

    @Test
    public void watchedShowsMostRecentFirst() throws Exception {
        List<Title> s = Trakt.watchedShows(WATCHED);
        assertEquals("Parks and Recreation", s.get(0).name);
        assertEquals(8546, s.get(0).trakt);
        assertEquals("tt0903747", s.get(1).imdb);
        assertEquals(81189, s.get(1).tvdb);
    }

    @Test
    public void nextEpisodeCarriesTheSeriesIds() throws Exception {
        Title show = Trakt.watchedShows(WATCHED).get(1);
        Title e = Trakt.nextEpisode(show, "{\"aired\":62,\"completed\":20,\"last_watched_at\":\"2026-09-01T09:10:11.000Z\","
                + "\"seasons\":[],\"next_episode\":{\"season\":2,\"number\":8,\"title\":\"Better Call Saul\","
                + "\"ids\":{\"trakt\":73524,\"tvdb\":438906,\"imdb\":\"tt1232244\",\"tmdb\":62092},"
                + "\"first_aired\":\"2009-04-27T02:00:00.000Z\",\"overview\":\"Walt and Jesse try to find a lawyer.\"}}");
        assertEquals(Title.Kind.EPISODE, e.kind);
        assertEquals("Breaking Bad", e.name);
        assertEquals(1396, e.tmdb);
        assertEquals("tt0903747", e.imdb);
        assertEquals("S02E08", e.episodeCode());
        assertEquals("2009-04-27", e.premiered);
        assertEquals("S02E08 · Better Call Saul", e.caption());
        assertNull(Trakt.nextEpisode(show, "{\"aired\":62,\"completed\":62,\"next_episode\":null}"));
    }

    @Test
    public void hiddenSeries() throws Exception {
        Set<Long> h = Trakt.hidden("[{\"hidden_at\":\"2026-03-30T23:18:42.000Z\",\"type\":\"show\","
                + "\"show\":{\"title\":\"Gossip Girl\",\"year\":2007,\"ids\":{\"trakt\":48,\"slug\":\"gossip-girl\",\"tvdb\":80547,\"imdb\":\"tt0397442\",\"tmdb\":1395}}}]");
        assertTrue(h.contains(48L));
    }

    @Test
    public void pausedFilmsAndEpisodes() throws Exception {
        List<Title> p = Trakt.playback("["
                + "{\"progress\":10,\"paused_at\":\"2026-10-24T22:00:00.000Z\",\"id\":13,\"type\":\"movie\","
                + "\"movie\":{\"title\":\"Batman Begins\",\"year\":2005,\"ids\":{\"trakt\":1,\"slug\":\"batman-begins-2005\",\"imdb\":\"tt0372784\",\"tmdb\":272}}},"
                + "{\"progress\":65.5,\"paused_at\":\"2026-10-23T22:00:00.000Z\",\"id\":37,\"type\":\"episode\","
                + "\"episode\":{\"season\":0,\"number\":1,\"title\":\"Good Cop Bad Cop\",\"ids\":{\"trakt\":1,\"tvdb\":3859781}},"
                + "\"show\":{\"title\":\"Breaking Bad\",\"year\":2008,\"ids\":{\"trakt\":1,\"slug\":\"breaking-bad\",\"tvdb\":81189,\"imdb\":\"tt0903747\",\"tmdb\":1396}}}"
                + "]");
        assertEquals(2, p.size());
        assertEquals(Title.Kind.MOVIE, p.get(0).kind);
        assertEquals(10, p.get(0).progress, 0.001);
        assertEquals(Title.Kind.EPISODE, p.get(1).kind);
        assertEquals(65.5, p.get(1).progress, 0.001);
        assertEquals(1396, p.get(1).tmdb);
    }

    @Test
    public void watchlist() throws Exception {
        List<Title> w = Trakt.parseWatchlist("["
                + "{\"rank\":1,\"id\":101,\"listed_at\":\"2026-06-17T06:52:03.000Z\",\"notes\":null,\"type\":\"movie\","
                + "\"movie\":{\"title\":\"Star Wars: The Force Awakens\",\"year\":2015,\"ids\":{\"trakt\":94024,\"slug\":\"star-wars-the-force-awakens-2015\",\"imdb\":\"tt2488496\",\"tmdb\":140607}}},"
                + "{\"rank\":2,\"id\":102,\"type\":\"season\",\"season\":{\"number\":1}},"
                + "{\"rank\":3,\"id\":103,\"type\":\"show\",\"show\":{\"title\":\"The Last of Us\",\"year\":2023,\"ids\":{\"trakt\":158947,\"imdb\":null,\"tmdb\":100088,\"tvdb\":null}}}"
                + "]");
        assertEquals(2, w.size());
        assertEquals(140607, w.get(0).tmdb);
        assertEquals(Title.Kind.SHOW, w.get(1).kind);
        assertEquals("", w.get(1).imdb);
        assertEquals(0, w.get(1).tvdb);
    }
}
