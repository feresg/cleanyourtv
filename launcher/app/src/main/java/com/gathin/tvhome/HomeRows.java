package com.gathin.tvhome;

/**
 * home_rows: Home keeps the dock and that many rows, and its last tile opens the App library, where every other app
 * is. An app is on Home exactly when its tile shows: the ones that don't fit are taken off Home (unticked in the
 * library), and ticking another is refused while Home is full.
 */
final class HomeRows {
    private HomeRows() { }

    /** The rows asked for, when they can apply: the library must be on (the way to the rest) and Home a grid. */
    static int effective(int rows, boolean library, boolean singleRow) {
        return library && !singleRow ? Math.max(0, Math.min(3, rows)) : 0;
    }

    /** Tiles Home keeps before the library tile: the dock and the rows, less the library tile's own slot. 0 when off. */
    static int capacity(int rows, int columns) {
        return rows > 0 && columns > 0 ? (1 + rows) * columns - 1 : 0;
    }

    /** True when Home has no slot left for another app. Never while home_rows is off (capacity 0). */
    static boolean full(int tiles, int capacity) {
        return capacity > 0 && tiles >= capacity;
    }
}
