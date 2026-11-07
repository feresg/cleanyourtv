package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeRowsTest {
    @Test public void twoRowsOfFourKeepSevenAppsAndTheLibraryTile() {
        // The dock and one row at 4 columns: 8 slots, the last one the App library tile.
        assertEquals(7, HomeRows.capacity(1, 4));
        assertEquals(11, HomeRows.capacity(2, 4));
        assertEquals(17, HomeRows.capacity(2, 6));
    }

    @Test public void offLeavesHomeAsItWas() {
        assertEquals(0, HomeRows.capacity(0, 4));
        assertFalse(HomeRows.full(500, 0));
    }

    @Test public void itNeedsTheLibraryAndAGrid() {
        assertEquals(1, HomeRows.effective(1, true, false));
        assertEquals(0, HomeRows.effective(1, false, false));   // no library: the other apps would be unreachable
        assertEquals(0, HomeRows.effective(1, true, true));     // single_row has no rows to cap
        assertEquals(3, HomeRows.effective(9, true, false));
        assertEquals(0, HomeRows.effective(-2, true, false));
    }

    @Test public void homeIsFullWhenEverySlotHasATile() {
        assertFalse(HomeRows.full(6, 7));
        assertTrue(HomeRows.full(7, 7));
        assertTrue(HomeRows.full(8, 7));
    }
}
