package io.github.feresg.watch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poster and backdrop images from TMDB, decoded as RGB_565 at about the size they are shown and kept in a memory
 * cache of an eighth of the heap, so a screen of rows fits a TV with 1 GB of RAM. Nothing is written to disk.
 */
final class Posters {
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Posters() {
    }

    /** Shows the image in the view once it is loaded, unless the view has been given another one meanwhile. */
    static void load(ImageView view, String url, int widthPx) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(url, widthPx);
            if (b == null) return;
            CACHE.put(url, b);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private static Bitmap fetch(String url, int widthPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) return null;
            byte[] data = read(c.getInputStream());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (widthPx > 0 && o.outWidth / (o.inSampleSize * 2) >= widthPx) o.inSampleSize *= 2;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("image too large");
            }
            return out.toByteArray();
        }
    }
}
