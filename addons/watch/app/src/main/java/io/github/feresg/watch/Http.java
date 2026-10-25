package io.github.feresg.watch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Plain HTTPS (and HTTP to Kodi on this TV). Blocking: call it off the main thread. */
final class Http {
    /** Status code (0 when the server couldn't be reached) and the body. */
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

    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private Http() {
    }

    static Reply get(String url, Map<String, String> headers) {
        return request("GET", url, headers, null, TIMEOUT_MS);
    }

    static Reply post(String url, Map<String, String> headers, String json) {
        return request("POST", url, headers, json, TIMEOUT_MS);
    }

    static Reply request(String method, String url, Map<String, String> headers, String json, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
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
