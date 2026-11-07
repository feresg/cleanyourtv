package io.github.feresg.watch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The keys and settings, in the app's own storage (backups are off). They arrive as config.json pushed by
 * install.sh, which is read once and deleted; the Trakt sign-in is kept here too.
 */
final class Config {
    static final String PUSHED = "config.json";
    static final List<String> DEFAULT_REGIONS = Arrays.asList("US", "GB", "FR");
    /** Trakt lets a development app redirect only to localhost; the device sign-in never opens it. */
    static final String DEFAULT_TRAKT_REDIRECT = "http://localhost";

    final String tmdbToken;
    final String traktId;
    /** Empty for new Trakt apps, which sign in without one; older apps were given one. */
    final String traktSecret;
    /** The Redirect URI entered on the Trakt app: Trakt checks it when a token is refreshed. */
    final String traktRedirect;
    final int kodiPort;
    final String kodiUser;
    final String kodiPass;
    final List<String> regions;
    final String language;
    /** The rows Watch offers Home, in order (Shelves.ALL by default). */
    final List<String> homeShelves;

    Config(String tmdbToken, String traktId, String traktSecret, String traktRedirect, int kodiPort, String kodiUser,
           String kodiPass, List<String> regions, String language) {
        this(tmdbToken, traktId, traktSecret, traktRedirect, kodiPort, kodiUser, kodiPass, regions, language,
                Arrays.asList(Shelves.ALL));
    }

    Config(String tmdbToken, String traktId, String traktSecret, String traktRedirect, int kodiPort, String kodiUser,
           String kodiPass, List<String> regions, String language, List<String> homeShelves) {
        this.tmdbToken = tmdbToken;
        this.traktId = traktId;
        this.traktSecret = traktSecret;
        this.traktRedirect = traktRedirect.isEmpty() ? DEFAULT_TRAKT_REDIRECT : traktRedirect;
        this.kodiPort = kodiPort;
        this.kodiUser = kodiUser;
        this.kodiPass = kodiPass;
        this.regions = regions;
        this.language = language;
        this.homeShelves = homeShelves;
    }

    boolean hasTmdb() {
        return !tmdbToken.isEmpty();
    }

    /** Trakt needs only the app's client id: new apps get no client secret, and older apps' secret is optional. */
    boolean hasTrakt() {
        return !traktId.isEmpty();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static SharedPreferences trakt(Context c) {
        return c.getSharedPreferences("trakt", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        SharedPreferences p = prefs(c);
        return new Config(p.getString("tmdb_token", ""), p.getString("trakt_client_id", ""),
                p.getString("trakt_client_secret", ""), p.getString("trakt_redirect_uri", DEFAULT_TRAKT_REDIRECT),
                p.getInt("kodi_port", 8080), p.getString("kodi_user", "kodi"),
                p.getString("kodi_pass", ""), regions(p.getString("regions", "")), p.getString("language", "en-US"),
                homeShelves(p.getString("home_shelves", null)));
    }

    /** "up_next,trending" to the known row ids it names, in order; all of them when unset (null). */
    static List<String> homeShelves(String csv) {
        if (csv == null) return Arrays.asList(Shelves.ALL);
        List<String> known = Arrays.asList(Shelves.ALL);
        List<String> out = new ArrayList<>();
        for (String id : csv.split(",")) {
            String t = id.trim();
            if (known.contains(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** "US,GB,FR" to a list of two-letter codes; the default when empty. UK is taken for GB. */
    static List<String> regions(String csv) {
        List<String> out = new ArrayList<>();
        for (String r : csv.split(",")) {
            String code = r.trim().toUpperCase(java.util.Locale.ROOT);
            if (code.equals("UK")) code = "GB";
            if (code.matches("[A-Z]{2}") && !out.contains(code)) out.add(code);
        }
        return out.isEmpty() ? DEFAULT_REGIONS : out;
    }

    /** Takes a pushed config.json if there is one, and deletes it. True when taken. */
    static boolean importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return false;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            SharedPreferences.Editor e = prefs(c).edit();
            for (String k : new String[]{"tmdb_token", "trakt_client_id", "trakt_client_secret", "trakt_redirect_uri", "kodi_user",
                    "kodi_pass", "language"}) {
                if (o.has(k)) e.putString(k, o.optString(k).trim());
            }
            if (o.has("kodi_port")) e.putInt("kodi_port", o.optInt("kodi_port", 8080));
            if (o.has("home_shelves")) {
                JSONArray a = o.optJSONArray("home_shelves");
                StringBuilder csv = new StringBuilder();
                for (int i = 0; a != null && i < a.length(); i++) csv.append(i > 0 ? "," : "").append(a.optString(i));
                e.putString("home_shelves", csv.toString());
            }
            if (o.has("regions")) {
                JSONArray a = o.optJSONArray("regions");
                StringBuilder csv = new StringBuilder();
                for (int i = 0; a != null && i < a.length(); i++) csv.append(i > 0 ? "," : "").append(a.optString(i));
                e.putString("regions", a == null ? o.optString("regions") : csv.toString());
            }
            e.apply();
            // New Trakt app keys: the old sign-in belonged to the old keys.
            if (o.has("trakt_client_id")) trakt(c).edit().clear().apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
