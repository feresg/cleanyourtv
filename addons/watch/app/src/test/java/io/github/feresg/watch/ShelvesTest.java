package io.github.feresg.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Home's rows from Watch: what is kept on disk, when it is read again, the pictures, and who may read them. */
public class ShelvesTest {
    private static Title episode() {
        Title t = new Title(Title.Kind.EPISODE);
        t.name = "Severance";
        t.year = 2022;
        t.tmdb = 95396;
        t.season = 2;
        t.episode = 4;
        t.episodeTitle = "Woe's Hollow";
        t.backdrop = "/bd123.jpg";
        t.poster = "/p123.jpg";
        t.progress = 42.6;
        return t;
    }

    @Test
    public void rowsSurviveTheTripToDiskAndBack() throws Exception {
        Title film = new Title(Title.Kind.MOVIE);
        film.name = "Dune: Part Two";
        film.tmdb = 693134;
        film.poster = "/dune.jpg";
        Shelves.Snapshot s = new Shelves.Snapshot(Arrays.asList(
                new Shelves.Shelf(Shelves.UP_NEXT, "Up next", Collections.singletonList(episode())),
                new Shelves.Shelf(Shelves.TRENDING, "Trending this week", Collections.singletonList(film))), 1234L);
        Shelves.Snapshot back = Shelves.fromJson(Shelves.toJson(s));
        assertEquals(1234L, back.fetchedAt);
        assertEquals(2, back.shelves.size());
        assertEquals("Up next", back.shelves.get(0).title);
        assertEquals("S02E04", back.shelves.get(0).titles.get(0).episodeCode());
        assertEquals("Dune: Part Two", back.find("movie:693134").name);
        assertEquals("Severance", back.find(episode().key()).name);
        assertNull(back.find("movie:1"));
    }

    @Test
    public void rowsAreReadAgainAfterHalfAnHour() {
        long now = 10_000_000L;
        assertTrue(Shelves.stale(0, now));
        assertFalse(Shelves.stale(now - 60_000, now));
        assertTrue(Shelves.stale(now - Shelves.FRESH_MS - 1, now));
        assertTrue("a clock that went back", Shelves.stale(now + 60_000, now));
    }

    @Test
    public void picturesAreTmdbPathsOnly() {
        assertEquals("/bd123.jpg", Shelves.imagePath(episode()));
        Title noBackdrop = episode();
        noBackdrop.backdrop = "";
        assertEquals("/p123.jpg", Shelves.imagePath(noBackdrop));
        assertEquals("bd123.jpg", Shelves.imageKey("/bd123.jpg"));
        assertNull(Shelves.imageKey("/../secret.jpg"));
        assertNull(Shelves.imageKey("https://example.com/x.jpg"));
        assertNull(Shelves.imageKey(""));
        assertTrue(Shelves.validKey("bd123.jpg"));
        assertFalse(Shelves.validKey("../shelves.json"));
        assertFalse(Shelves.validKey("x.json"));
        assertEquals(Collections.singletonList("old.jpg"),
                Shelves.unused(Arrays.asList("bd123.jpg", "old.jpg"), new HashSet<>(Collections.singletonList("bd123.jpg"))));
    }

    @Test
    public void newOnStreamingTakesFilmsAndSeriesInTurn() {
        Title f1 = new Title(Title.Kind.MOVIE), f2 = new Title(Title.Kind.MOVIE), s1 = new Title(Title.Kind.SHOW);
        f1.tmdb = 1;
        f2.tmdb = 2;
        s1.tmdb = 3;
        List<Title> mixed = Shelves.interleave(Arrays.asList(f1, f2), Collections.singletonList(s1));
        assertEquals(Arrays.asList(f1, s1, f2), mixed);
    }

    @Test
    public void theRowsToOfferComeFromTheConfig() {
        assertEquals(Arrays.asList(Shelves.ALL), Config.homeShelves(null));
        assertEquals(Arrays.asList("trending", "up_next"), Config.homeShelves("trending, up_next,bogus,trending"));
        assertTrue(Config.homeShelves("").isEmpty());
    }

    @Test
    public void homeGetsANewRowForEachCountryAskedFor() {
        List<String> regions = Arrays.asList("US", "GB", "FR");
        assertEquals(Arrays.asList("US", "FR"), Config.homeRegions("us, fr,FR,xx1", regions));
        assertEquals("the first country by default", Collections.singletonList("US"), Config.homeRegions("", regions));
        assertEquals(Collections.singletonList("US"), Config.homeRegions(null, regions));
        assertEquals("new_fr", Shelves.newId("FR"));
    }

    @Test
    public void onlyTheLauncherSignedLikeWatchMayRead() {
        assertTrue(ShelfProvider.allowed("com.gathin.tvhome", PackageManager.SIGNATURE_MATCH));
        assertFalse(ShelfProvider.allowed("com.gathin.tvhome", PackageManager.SIGNATURE_NO_MATCH));
        assertFalse(ShelfProvider.allowed("com.example.spy", PackageManager.SIGNATURE_MATCH));
        assertFalse(ShelfProvider.allowed(null, PackageManager.SIGNATURE_MATCH));
    }

    @Test
    public void aCardCarriesItsKeyLineProgressAndPicture() throws Exception {
        File dir = Files.createTempDirectory("shelf-images").toFile();
        Object[] noPicture = ShelfProvider.itemRow(episode(), "io.github.feresg.watch.shelves", dir);
        assertEquals(episode().key(), noPicture[0]);
        assertEquals("Severance", noPicture[1]);
        assertEquals("S02E04 · Woe's Hollow", noPicture[2]);
        assertEquals(43, noPicture[3]);
        assertEquals("", noPicture[4]);
        assertTrue(new File(dir, "bd123.jpg").createNewFile());
        Object[] withPicture = ShelfProvider.itemRow(episode(), "io.github.feresg.watch.shelves", dir);
        assertEquals("content://io.github.feresg.watch.shelves/images/bd123.jpg", withPicture[4]);
        Title film = new Title(Title.Kind.MOVIE);
        film.name = "Dune";
        film.year = 2024;
        assertEquals(-1, ShelfProvider.itemRow(film, "a", dir)[3]);
        assertEquals("2024", ShelfProvider.itemRow(film, "a", dir)[2]);
    }
}
