package io.github.feresg.hue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTP to the bridge (and once to Signify's discovery address). Blocking: call it off the main thread. */
final class Bridge {
    static final String DISCOVERY = "https://discovery.meethue.com/";

    static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final String host;
    private final String user;

    Bridge(String host, String user) {
        this.host = host;
        this.user = user;
    }

    Reply groups() {
        return request("GET", "http://" + host + "/api/" + user + "/groups", null);
    }

    Reply scenes() {
        return request("GET", "http://" + host + "/api/" + user + "/scenes", null);
    }

    Reply action(String groupId, String body) {
        return request("PUT", "http://" + host + "/api/" + user + "/groups/" + groupId + "/action", body);
    }

    /** Asks for a username; it is given only within 30 s of the bridge's link button being pressed. */
    Reply pair() {
        return request("POST", "http://" + host + "/api", Hue.pairBody());
    }

    static Reply discover() {
        return request("GET", DISCOVERY, null);
    }

    static Reply request(String method, String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(b);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Reply(code, in == null ? "" : read(in));
        } catch (IOException | RuntimeException e) {
            return new Reply(0, String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) break;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
