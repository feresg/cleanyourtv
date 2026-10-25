package io.github.feresg.vpnstatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * What NordVPN's public ip-insights address says about the connection the TV uses: the public IP, where it
 * appears to be, the ISP, and whether that IP is one of NordVPN's servers. Blocking: call it off the main thread.
 */
final class Insights {
    static final String URL = "https://api.nordvpn.com/v1/helpers/ips/insights";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BODY = 64 * 1024;

    final String ip;
    final String country;
    final String countryCode;
    final String city;
    final String isp;
    /** True when the public IP belongs to a NordVPN server. */
    final boolean isProtected;

    Insights(String ip, String country, String countryCode, String city, String isp, boolean isProtected) {
        this.ip = ip;
        this.country = country;
        this.countryCode = countryCode;
        this.city = city;
        this.isp = isp;
        this.isProtected = isProtected;
    }

    /** "Paris, France", or whichever part is known. */
    String place() {
        if (!city.isEmpty() && !country.isEmpty()) return city + ", " + country;
        return city.isEmpty() ? country : city;
    }

    static Insights parse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        if (!o.has("ip")) throw new JSONException("no ip");
        return new Insights(text(o, "ip"), text(o, "country"), text(o, "country_code"), text(o, "city"),
                text(o, "isp"), o.optBoolean("protected", false));
    }

    private static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }

    /** The answer, or null with the reason in why[0]. */
    static Insights fetch(String[] why) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) {
                why[0] = "NordVPN's address answered " + code + ".";
                return null;
            }
            return parse(read(c.getInputStream()));
        } catch (JSONException e) {
            why[0] = "NordVPN's answer couldn't be read.";
        } catch (IOException | RuntimeException e) {
            why[0] = "No internet connection, or NordVPN's address can't be reached.";
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) break;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
