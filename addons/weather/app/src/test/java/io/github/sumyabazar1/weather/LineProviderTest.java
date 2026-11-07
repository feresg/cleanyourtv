package io.github.sumyabazar1.weather;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The launcher's line under its clock: when it is read again, and how long an old reading still shows. */
public class LineProviderTest {
    private static final long NOW = 100_000_000L;

    @Test
    public void readsAgainAfterHalfAnHourButNotRightAfterAFailure() {
        assertTrue("never read", LineProvider.due(0, 0, NOW));
        assertFalse("fresh", LineProvider.due(NOW - 60_000, 0, NOW));
        assertTrue("old", LineProvider.due(NOW - Forecast.REFRESH_MS - 1, 0, NOW));
        assertFalse("tried a minute ago", LineProvider.due(NOW - Forecast.REFRESH_MS - 1, NOW - 60_000, NOW));
        assertTrue("tried long ago", LineProvider.due(NOW - Forecast.REFRESH_MS - 1, NOW - Forecast.RETRY_MS - 1, NOW));
        assertTrue("a clock that went back", LineProvider.due(NOW + 60_000, 0, NOW));
    }

    @Test
    public void anOldReadingIsNotShown() {
        assertTrue(LineProvider.shown(NOW - 60_000, NOW));
        assertTrue(LineProvider.shown(NOW - LineProvider.SHOW_MS, NOW));
        assertFalse(LineProvider.shown(NOW - LineProvider.SHOW_MS - 1, NOW));
        assertFalse(LineProvider.shown(0, NOW));
        assertFalse(LineProvider.shown(NOW + 1, NOW));
    }
}
