package io.github.feresg.hue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** The Hue Bridge's local API v1, as far as this screen uses it: rooms and zones, their scenes, and pairing. */
final class Hue {
    static final int BRI_MAX = 254;
    /** One press of Left or Right: a tenth of the range. */
    static final int BRI_STEP = 25;

    static final class Group {
        final String id;
        final String name;
        /** "Room" or "Zone". */
        final String type;
        final Set<String> lights;
        final boolean anyOn;
        final boolean allOn;
        /** 1 to 254, the group's last brightness. */
        final int bri;

        Group(String id, String name, String type, Set<String> lights, boolean anyOn, boolean allOn, int bri) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.lights = lights;
            this.anyOn = anyOn;
            this.allOn = allOn;
            this.bri = bri;
        }

        int percent() {
            return Math.max(1, Math.round(bri * 100f / BRI_MAX));
        }
    }

    static final class Scene {
        final String id;
        final String name;

        Scene(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Hue() {
    }

    /** The bridge's error description when it answers with an error list ([{"error": ...}]), or null. */
    static String error(String body) {
        try {
            Object v = new org.json.JSONTokener(body).nextValue();
            if (!(v instanceof JSONArray)) return null;
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i) == null ? null : a.optJSONObject(i).optJSONObject("error");
                if (e != null) return e.optString("description", "error " + e.optInt("type"));
            }
        } catch (JSONException ignored) {
            // Not JSON: no bridge error.
        }
        return null;
    }

    /** True when the error list says the username is no longer known to the bridge (type 1). */
    static boolean unauthorized(String body) {
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                JSONObject e = o == null ? null : o.optJSONObject("error");
                if (e != null && e.optInt("type") == 1) return true;
            }
        } catch (JSONException ignored) {
            // Not an error list.
        }
        return false;
    }

    /** The new username from POST /api, or null while the link button hasn't been pressed (error 101). */
    static String username(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            JSONObject s = o == null ? null : o.optJSONObject("success");
            if (s != null && !s.optString("username").isEmpty()) return s.optString("username");
        }
        return null;
    }

    static String pairBody() {
        try {
            return new JSONObject().put("devicetype", "cleanyourtv#tv").toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rooms first, then zones, each sorted by name. */
    static List<Group> groups(String body) throws JSONException {
        JSONObject all = new JSONObject(body);
        List<Group> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject g = all.optJSONObject(id);
            if (g == null) continue;
            String type = g.optString("type");
            if (!"Room".equals(type) && !"Zone".equals(type)) continue;
            Set<String> lights = new HashSet<>();
            JSONArray l = g.optJSONArray("lights");
            for (int i = 0; l != null && i < l.length(); i++) lights.add(l.optString(i));
            if (lights.isEmpty()) continue;
            JSONObject state = g.optJSONObject("state");
            JSONObject action = g.optJSONObject("action");
            int bri = action == null ? BRI_MAX : action.optInt("bri", BRI_MAX);
            out.add(new Group(id, g.optString("name", "Room " + id), type, lights,
                    state != null && state.optBoolean("any_on"), state != null && state.optBoolean("all_on"),
                    Math.max(1, Math.min(BRI_MAX, bri))));
        }
        Collections.sort(out, (a, b) -> {
            if (!a.type.equals(b.type)) return "Room".equals(a.type) ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    /**
     * The scenes for one room or zone, by name: group scenes made for it, and light scenes whose lights are all in
     * it. Scenes the bridge may recycle (temporary ones apps make) are left out.
     */
    static List<Scene> scenes(String body, Group group) throws JSONException {
        JSONObject all = new JSONObject(body);
        List<Scene> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject s = all.optJSONObject(id);
            if (s == null || s.optBoolean("recycle")) continue;
            boolean mine;
            if ("GroupScene".equals(s.optString("type"))) {
                mine = group.id.equals(s.optString("group"));
            } else {
                JSONArray l = s.optJSONArray("lights");
                mine = l != null && l.length() > 0;
                for (int i = 0; mine && i < l.length(); i++) mine = group.lights.contains(l.optString(i));
            }
            if (mine) out.add(new Scene(id, s.optString("name", id)));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static String on(boolean on) {
        return "{\"on\":" + on + "}";
    }

    /** Brightness 1 to 254, switching the group on; 0 or less switches it off. */
    static String bri(int bri) {
        if (bri <= 0) return on(false);
        return "{\"on\":true,\"bri\":" + Math.min(BRI_MAX, bri) + "}";
    }

    static String scene(String id) {
        try {
            return new JSONObject().put("scene", id).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The next brightness after one press, from the group's current state. Off and Right starts at one step. */
    static int step(boolean on, int bri, int direction) {
        if (!on) return direction > 0 ? BRI_STEP : 0;
        int next = bri + direction * BRI_STEP;
        if (direction < 0 && bri <= 1) return 0;
        return Math.max(1, Math.min(BRI_MAX, next));
    }

    /** Bridge addresses from discovery.meethue.com ([{"id": ..., "internalipaddress": ...}]). */
    static List<String> discovered(String body) throws JSONException {
        JSONArray a = new JSONArray(body);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String ip = o == null ? "" : o.optString("internalipaddress").trim();
            if (validHost(ip) && !out.contains(ip)) out.add(ip);
        }
        return out;
    }

    /** An IPv4 address or a host name, no scheme, port or path. */
    static boolean validHost(String host) {
        return host != null && host.matches("[A-Za-z0-9.-]{1,253}") && !host.startsWith(".") && !host.startsWith("-");
    }
}
