package io.github.feresg.watch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kodi on this TV: brings it to the front, waits for its JSON-RPC web server (Settings, Services, Control, Allow
 * remote control via HTTP) and asks it to play an Umbrella address or open an Umbrella page.
 */
final class Kodi {
    static final String PACKAGE = "org.xbmc.kodi";
    /** Kodi takes a few seconds to start on a cold TV. */
    private static final int TRIES = 20;
    private static final long TRY_EVERY_MS = 1000;
    private static final int TIMEOUT_MS = 3000;

    private Kodi() {
    }

    static Intent launchIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(PACKAGE);
        return i != null ? i : pm.getLaunchIntentForPackage(PACKAGE);
    }

    /** JSON-RPC: play a file or plugin address, as Kodi's PlayMedia does. */
    static String playBody(String file) {
        try {
            return request("Player.Open", new JSONObject().put("item", new JSONObject().put("file", file)));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON-RPC: open a plugin page in the Videos window. */
    static String openBody(String url) {
        try {
            return request("GUI.ActivateWindow", new JSONObject().put("window", "videos")
                    .put("parameters", new JSONArray().put(url).put("return")));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pingBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"JSONRPC.Ping\"}";
    }

    private static String request(String method, JSONObject params) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString();
    }

    /** Null when Kodi's answer is a result, else what went wrong. */
    static String error(Http.Reply r) {
        if (r.code == 401) return "Kodi refused the user name or password.";
        if (r.code == 0) return "Kodi's remote control over HTTP is off (Settings, Services, Control).";
        if (!r.ok()) return "Kodi answered " + r.code + ".";
        try {
            JSONObject o = new JSONObject(r.body);
            if (o.has("error")) return "Kodi: " + o.getJSONObject("error").optString("message", "error");
            return null;
        } catch (JSONException e) {
            return "Kodi's answer couldn't be read.";
        }
    }

    /**
     * Sends one request once Kodi answers. Blocking, up to about 20 s while Kodi starts: call it off the main
     * thread after Kodi has been brought to the front. Null on success, else what went wrong.
     */
    static String send(Config config, String body) {
        String url = "http://127.0.0.1:" + config.kodiPort + "/jsonrpc";
        Map<String, String> h = new HashMap<>();
        if (!config.kodiPass.isEmpty() || !config.kodiUser.isEmpty()) {
            String auth = config.kodiUser + ":" + config.kodiPass;
            h.put("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        Http.Reply ping = null;
        for (int i = 0; i < TRIES; i++) {
            ping = Http.request("POST", url, h, pingBody(), TIMEOUT_MS);
            if (ping.code != 0) break;
            try {
                Thread.sleep(TRY_EVERY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Stopped.";
            }
        }
        String why = error(ping);
        if (why != null) return why;
        return error(Http.request("POST", url, h, body, TIMEOUT_MS * 3));
    }
}
