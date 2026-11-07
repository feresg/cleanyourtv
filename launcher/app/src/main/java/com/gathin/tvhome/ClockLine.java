package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.Set;

/**
 * clock_line: one line under the clock, such as the weather, read from a module whose card the owner chose to show
 * (docs/specs/clock-line.md). The card's activity names a content provider of its own app in the {@link #META}
 * meta-data; the launcher reads the {@code text} column of the first row of {@code content://<authority>/line}. The
 * launcher has no network: the module does any fetching. Same checks and guard as HomeShelves.
 */
final class ClockLine {
    static final String META = "com.gathin.tvhome.clock_line";
    static final int TEXT_MAX = 48;

    /** What one read found: the provider (to listen to) and its line, "" while it has none. */
    static final class Line {
        final String authority;
        final String text;

        Line(String authority, String text) {
            this.authority = authority;
            this.text = text;
        }
    }

    private ClockLine() {
    }

    static Uri uri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("line").build();
    }

    /** The line of the first shown card that offers one, or null when none does. Blocking: the loader thread only. */
    static Line read(Context ctx, List<Cards.Card> shown, Set<String> logged) {
        ContentResolver cr = ctx.getContentResolver();
        for (Cards.Card card : shown) {
            String authority = HomeShelves.authority(ctx.getPackageManager(), card, META, logged);
            if (authority == null) continue;
            try (Cursor c = cr.query(uri(authority), null, null, null, null)) {
                String text = null;
                if (c != null && c.moveToFirst()) {
                    int col = c.getColumnIndex("text");
                    if (col >= 0 && !c.isNull(col)) text = c.getString(col);
                }
                return new Line(authority, HomeShelves.clean(text, TEXT_MAX));
            } catch (RuntimeException e) {
                if (logged.add(card.pkg)) Log.w(HomeShelves.TAG, card.pkg + ": " + e);
            }
        }
        return null;
    }
}
