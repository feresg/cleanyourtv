package io.github.feresg.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

/** The Umbrella addresses, as its router reads them, and the Kodi JSON-RPC requests that carry them. */
public class UmbrellaTest {
    private static Title episode() {
        Title t = new Title(Title.Kind.EPISODE);
        t.name = "Breaking Bad";
        t.year = 2008;
        t.imdb = "tt0903747";
        t.tmdb = 1396;
        t.tvdb = 81189;
        t.season = 2;
        t.episode = 8;
        t.episodeTitle = "Better Call Saul";
        t.premiered = "2009-04-26";
        return t;
    }

    @Test
    public void playsAFilm() {
        Title t = new Title(Title.Kind.MOVIE);
        t.name = "Amélie & Nino";
        t.year = 2001;
        t.imdb = "tt0211915";
        t.tmdb = 194;
        assertEquals("plugin://plugin.video.umbrella/?action=play_Item&title=Am%C3%A9lie+%26+Nino&year=2001&imdb=tt0211915&tmdb=194",
                Umbrella.play(t));
    }

    @Test
    public void playsAnEpisodeByItsSeries() {
        assertEquals("plugin://plugin.video.umbrella/?action=play_Item&title=Better+Call+Saul&year=2008&imdb=tt0903747"
                + "&tmdb=1396&tvdb=81189&season=2&episode=8&tvshowtitle=Breaking+Bad&premiered=2009-04-26", Umbrella.play(episode()));
    }

    @Test
    public void opensSeasonsAndEpisodes() {
        assertEquals("plugin://plugin.video.umbrella/?action=seasons&tvshowtitle=Breaking+Bad&year=2008&imdb=tt0903747&tmdb=1396&tvdb=81189",
                Umbrella.seasons(episode()));
        assertEquals("plugin://plugin.video.umbrella/?action=episodes&tvshowtitle=Breaking+Bad&year=2008&imdb=tt0903747&tmdb=1396&tvdb=81189&season=2",
                Umbrella.episodes(episode()));
        Title noIds = new Title(Title.Kind.SHOW);
        noIds.name = "Lupin";
        assertEquals("plugin://plugin.video.umbrella/?action=seasons&tvshowtitle=Lupin&year=&imdb=&tmdb=&tvdb=", Umbrella.seasons(noIds));
    }

    @Test
    public void kodiRequests() throws Exception {
        JSONObject play = new JSONObject(Kodi.playBody("plugin://plugin.video.umbrella/?action=play_Item&title=X"));
        assertEquals("Player.Open", play.getString("method"));
        assertEquals("plugin://plugin.video.umbrella/?action=play_Item&title=X",
                play.getJSONObject("params").getJSONObject("item").getString("file"));
        JSONObject open = new JSONObject(Kodi.openBody("plugin://p/?a=1"));
        assertEquals("GUI.ActivateWindow", open.getString("method"));
        assertEquals("videos", open.getJSONObject("params").getString("window"));
        assertEquals("plugin://p/?a=1", open.getJSONObject("params").getJSONArray("parameters").getString(0));
        assertEquals("JSONRPC.Ping", new JSONObject(Kodi.pingBody()).getString("method"));
    }

    @Test
    public void kodiAnswers() {
        assertNull(Kodi.error(new Http.Reply(200, "{\"id\":1,\"jsonrpc\":\"2.0\",\"result\":\"OK\"}")));
        assertEquals("Kodi: Invalid params.", Kodi.error(new Http.Reply(200, "{\"error\":{\"code\":-32602,\"message\":\"Invalid params.\"},\"id\":1}")));
        assertEquals("Kodi refused the user name or password.", Kodi.error(new Http.Reply(401, "")));
        assertEquals("Kodi's remote control over HTTP is off (Settings, Services, Control).", Kodi.error(new Http.Reply(0, "refused")));
    }

    @Test
    public void titleRoundTrip() throws Exception {
        Title t = Title.fromJson(episode().toJson());
        assertEquals("S02E08", t.episodeCode());
        assertEquals(81189, t.tvdb);
        assertEquals(-1, t.progress, 0.001);
    }
}
