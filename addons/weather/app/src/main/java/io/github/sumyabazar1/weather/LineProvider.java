package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One line for the Clean Your TV launcher's clock_line switch, "12° · Partly cloudy · Paris", under its clock.
 * {@code content://<package>.clockline/line} answers at once from the last reading (column {@code text}); when that
 * reading is over 30 minutes old it reads Open-Meteo again on a thread of its own and tells the launcher, which reads
 * the line afresh. A failed read is tried again 5 minutes later at the earliest. No service and no timer: nothing is
 * fetched unless the launcher asks. The weather of a city is no secret, so any app may read the line.
 */
public class LineProvider extends ContentProvider {
    private static final String PREFS = "line";
    /** A reading older than this is not shown: better no line than one from this morning. */
    static final long SHOW_MS = 3 * 60 * 60_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean READING = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    static Uri uri(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".clockline/line");
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Context c = getContext();
        SharedPreferences line = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), at = line.getLong("at", 0), tried = line.getLong("tried", 0);
        if (due(at, tried, now)) refresh(c);
        MatrixCursor out = new MatrixCursor(new String[]{"text"});
        String text = line.getString("text", "");
        if (!text.isEmpty() && shown(at, now)) out.addRow(new Object[]{text});
        out.setNotificationUri(c.getContentResolver(), uri(c));
        return out;
    }

    /** True when the reading at {@code at} should be read again: older than REFRESH_MS, and no try in RETRY_MS. */
    static boolean due(long at, long tried, long now) {
        boolean old = at <= 0 || at > now || now - at > Forecast.REFRESH_MS;
        boolean rested = tried <= 0 || tried > now || now - tried > Forecast.RETRY_MS;
        return old && rested;
    }

    /** True while a reading is recent enough to show. */
    static boolean shown(long at, long now) {
        return at > 0 && at <= now && now - at <= SHOW_MS;
    }

    /** The line for a reading: degrees in the options' unit, the sky, the place. */
    static String text(Context c, Forecast.Report r, String place) {
        boolean fahrenheit = "f".equalsIgnoreCase(c.getString(R.string.temperature_unit).trim());
        String line = Forecast.degrees(r.tempC, fahrenheit) + "° · " + c.getString(Forecast.sky(r.code, r.day));
        return place.isEmpty() ? line : line + " · " + place;
    }

    /** Keeps a reading's line (the weather page calls this too) and tells the launcher. */
    static void save(Context c, Forecast.Report r) {
        String place = c.getSharedPreferences(WeatherActivity.PREFS, Context.MODE_PRIVATE).getString("name", "");
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("text", text(c, r, place)).putLong("at", System.currentTimeMillis()).apply();
        c.getContentResolver().notifyChange(uri(c), null);
    }

    private static void refresh(Context c) {
        SharedPreferences place = c.getSharedPreferences(WeatherActivity.PREFS, Context.MODE_PRIVATE);
        if (!place.contains("name") || !READING.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("tried", System.currentTimeMillis()).apply();
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        IO.execute(() -> {
            try {
                save(app, Forecast.fetch(lat, lon, Forecast.MIN_DAYS));
            } catch (Exception ignored) {
                // The last line stays until it is too old to show; the launcher asks again later.
            } finally {
                READING.set(false);
            }
        });
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
