package com.gathin.tvhome;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** home_shelves: what the launcher accepts from a module's provider, and how far Home moves for a row. */
public class HomeShelvesTest {
    private static final String WATCH = "io.github.feresg.watch.shelves";

    @After
    public void tearDown() {
        HomeShelves.reset();
    }

    @Test public void anAuthorityIsOneAsAManifestWritesIt() {
        assertTrue(HomeShelves.validAuthority(WATCH));
        assertTrue(HomeShelves.validAuthority("a-b_c.d9"));
        assertFalse(HomeShelves.validAuthority(null));
        assertFalse(HomeShelves.validAuthority(""));
        assertFalse(HomeShelves.validAuthority(".watch"));
        assertFalse(HomeShelves.validAuthority("watch."));
        assertFalse(HomeShelves.validAuthority("io..watch"));
        assertFalse(HomeShelves.validAuthority("io.watch/shelves"));
        assertFalse(HomeShelves.validAuthority("user@io.watch"));
        assertFalse(HomeShelves.validAuthority("io.watch:8080"));
        assertFalse(HomeShelves.validAuthority("one;two"));
        assertFalse(HomeShelves.validAuthority(new String(new char[HomeShelves.AUTHORITY_MAX + 1]).replace('\0', 'a')));
    }

    @Test public void picturesComeOnlyFromTheRowsOwnProvider() {
        assertTrue(HomeShelves.imageAllowed("content://" + WATCH + "/images/bd123.jpg", WATCH));
        assertFalse("another app's provider", HomeShelves.imageAllowed("content://com.example.photos/images/a.jpg", WATCH));
        assertFalse("a longer authority that starts the same",
                HomeShelves.imageAllowed("content://" + WATCH + "x/images/a.jpg", WATCH));
        assertFalse("no network", HomeShelves.imageAllowed("https://image.tmdb.org/t/p/w300/a.jpg", WATCH));
        assertFalse(HomeShelves.imageAllowed("file:///sdcard/a.jpg", WATCH));
        assertFalse(HomeShelves.imageAllowed("content://" + WATCH + "/", WATCH));
        assertFalse(HomeShelves.imageAllowed("content://" + WATCH + "/images/", WATCH));
        assertFalse(HomeShelves.imageAllowed("content://" + WATCH + "/images/../secret", WATCH));
        assertFalse(HomeShelves.imageAllowed("content://" + WATCH + "//a.jpg", WATCH));
        assertFalse("no escapes to decode", HomeShelves.imageAllowed("content://" + WATCH + "/images/%2e%2e", WATCH));
        assertFalse(HomeShelves.imageAllowed("content://" + WATCH + "/images/a.jpg?x=1", WATCH));
        assertFalse(HomeShelves.imageAllowed(null, WATCH));
        assertFalse(HomeShelves.imageAllowed("", WATCH));
    }

    @Test public void idsAndLinesAreBoundedAndCleaned() {
        assertEquals("episode:95396:2:4", HomeShelves.cleanId("episode:95396:2:4"));
        assertNull(HomeShelves.cleanId(null));
        assertNull(HomeShelves.cleanId(""));
        assertNull(HomeShelves.cleanId("a\nb"));
        assertNull(HomeShelves.cleanId(new String(new char[HomeShelves.ID_MAX + 1]).replace('\0', 'x')));

        assertEquals("Up next", HomeShelves.clean("  Up‮next\n", HomeShelves.HEADING_MAX));
        assertEquals("", HomeShelves.clean(null, HomeShelves.TITLE_MAX));
        assertEquals("abcd", HomeShelves.clean("abcdefgh", 4));
        assertEquals("S02E04 · Woe's Hollow", HomeShelves.clean("S02E04 · Woe's Hollow", HomeShelves.SUBTITLE_MAX));
    }

    @Test public void theBarIsAShareOfAHundredOrNone() {
        assertEquals(-1, HomeShelves.progress(-1));
        assertEquals(-1, HomeShelves.progress(Integer.MIN_VALUE));
        assertEquals(0, HomeShelves.progress(0));
        assertEquals(43, HomeShelves.progress(43));
        assertEquals(100, HomeShelves.progress(250));
    }

    @Test public void aPictureFileIsReadWholeOrNotAtAll() throws Exception {
        byte[] small = {1, 2, 3, 4, 5};
        assertArrayEquals(small, HomeShelves.readAtMost(new ByteArrayInputStream(small), 5));
        assertNull(HomeShelves.readAtMost(new ByteArrayInputStream(small), 4));
        byte[] big = new byte[40_000];
        assertEquals(40_000, HomeShelves.readAtMost(new ByteArrayInputStream(big), 40_000).length);
        assertNull(HomeShelves.readAtMost(new ByteArrayInputStream(big), 39_999));
    }

    @Test public void homeMovesUpJustEnoughForTheFocusedRow() {
        // A 540 high screen with 44 of the rows showing at rest: their top is at 496.
        int rest = 496, focusY = 150, bottom = 520;
        // Four rows of 150: the first comes to y 150, Home moving up 346.
        assertEquals(-346, HomeShelves.pageOffset(rest, 0, focusY, 600, bottom));
        assertEquals(-496, HomeShelves.pageOffset(rest, 150, focusY, 600, bottom));
        // The last row stops at the bottom instead of leaving empty screen under it.
        assertEquals(-576, HomeShelves.pageOffset(rest, 450, focusY, 600, bottom));
        // One row: Home moves only as far as it takes to show it whole.
        assertEquals(-126, HomeShelves.pageOffset(rest, 0, focusY, 150, bottom));
        // Never down.
        assertEquals(0, HomeShelves.pageOffset(100, 0, focusY, 50, bottom));
    }

    @Test public void comingBackToARowLandsOnTheCardLastFocusedThere() {
        assertEquals(2, HomeShelves.clampIndex(5, 3));
        assertEquals(0, HomeShelves.clampIndex(-1, 3));
        assertEquals(1, HomeShelves.clampIndex(1, 3));
        assertEquals(-1, HomeShelves.clampIndex(0, 0));
    }

    @Test public void aReadThatShowsNothingNewChangesNothingOnScreen() {
        List<HomeShelves.Row> a = Collections.singletonList(row("up_next", item("e1", 40), item("e2", -1)));
        assertTrue(HomeShelves.same(a, Collections.singletonList(row("up_next", item("e1", 40), item("e2", -1)))));
        assertFalse(HomeShelves.same(a, Collections.singletonList(row("up_next", item("e1", 41), item("e2", -1)))));
        assertFalse(HomeShelves.same(a, Collections.singletonList(row("up_next", item("e2", -1), item("e1", 40)))));
        assertFalse(HomeShelves.same(a, Collections.singletonList(row("trending", item("e1", 40), item("e2", -1)))));
        assertFalse(HomeShelves.same(a, Arrays.asList(row("up_next", item("e1", 40), item("e2", -1)), row("new", item("m", -1)))));
        assertTrue(HomeShelves.same(Collections.emptyList(), Collections.emptyList()));
    }

    @Test public void aFailureSwitchesTheRowsOffForTheSession() {
        assertTrue(HomeShelves.working());
        HomeShelves.fail(new IllegalStateException("test"));
        assertFalse(HomeShelves.working());
        HomeShelves.reset();
        assertTrue(HomeShelves.working());
    }

    private static HomeShelves.Row row(String id, HomeShelves.Item... items) {
        return new HomeShelves.Row("io.github.feresg.watch", null, WATCH, id, id, Arrays.asList(items));
    }

    private static HomeShelves.Item item(String id, int progress) {
        return new HomeShelves.Item(id, "Title " + id, "", progress, null, null);
    }
}
