package io.github.feresg.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The tile and module card open this: tabs for what you are watching on Trakt (Up next, paused, the watchlist) and
 * for what is trending and new on streaming in your countries, from TMDB, as rows of posters. OK on a poster opens
 * it, and from there Umbrella plays it in Kodi. It reads Trakt and TMDB only while this screen is open.
 */
public class MainActivity extends Activity {
    enum Tab { UP_NEXT, PAUSED, WATCHLIST, TRENDING, NEW }

    private static final String[] TAB_NAMES = {"Up next", "Continue", "Watchlist", "Trending", "New on streaming"};
    /** A tab's rows are read again after this long. */
    private static final long FRESH_MS = 10 * 60 * 1000L;
    private static final int ROW_MAX = 20;
    /** Moving along the tabs switches the page once focus stops for this long. */
    private static final long TAB_SETTLE_MS = 250;

    /** One row: a heading and its titles. */
    static final class Shelf {
        final String heading;
        final List<Title> titles;

        Shelf(String heading, List<Title> titles) {
            this.heading = heading;
            this.titles = titles;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Map<Tab, List<Shelf>> loaded = new HashMap<>();
    private final Map<Tab, Long> loadedAt = new HashMap<>();
    private final List<Button> tabs = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private Config config;
    private Trakt trakt;
    private Tab tab = Tab.UP_NEXT;
    private Tab pendingTab;
    private int signInGeneration;
    private final Runnable switchTab = () -> {
        if (pendingTab != null && pendingTab != tab) show(pendingTab);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0E0F13);
        page.setPadding(px(48), px(28), px(48), px(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(26);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, px(24), 0);
        top.addView(title);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            Tab t = Tab.values()[i];
            Button b = new Button(this);
            b.setText(TAB_NAMES[i]);
            b.setAllCaps(false);
            b.setTextSize(15);
            b.setTextColor(getColorStateList(R.color.button_text));
            b.setBackgroundResource(R.drawable.button);
            b.setPadding(px(18), 0, px(18), 0);
            b.setOnClickListener(v -> show(t));
            b.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                pendingTab = t;
                main.removeCallbacks(switchTab);
                main.postDelayed(switchTab, TAB_SETTLE_MS);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
            lp.rightMargin = px(10);
            top.addView(b, lp);
            tabs.add(b);
        }
        page.addView(top);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(0xFF9DA1AB);
        status.setPadding(0, px(10), 0, px(4));
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(8), 0, px(24));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean imported = Config.importPushed(this);
        if (imported) {
            loaded.clear();
            loadedAt.clear();
        }
        config = Config.load(this);
        trakt = new Trakt(config, Config.trakt(this));
        boolean first = content.getChildCount() == 0 && loaded.isEmpty();
        // Without Trakt keys, open on what TMDB can show.
        if (first && !config.hasTrakt() && config.hasTmdb() && tab.ordinal() < Tab.TRENDING.ordinal()) tab = Tab.TRENDING;
        boolean signingIn = tab.ordinal() < Tab.TRENDING.ordinal() && config.hasTrakt() && !trakt.signedIn();
        // Back from a title: keep the rows and the focus as they were.
        if (first || imported || signingIn || content.getChildCount() == 0) show(tab);
        if (first) tabs.get(tab.ordinal()).requestFocus();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(switchTab);
        signInGeneration++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private void show(Tab t) {
        tab = t;
        pendingTab = null;
        signInGeneration++;
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(i == t.ordinal());
        content.removeAllViews();
        boolean traktTab = t.ordinal() < Tab.TRENDING.ordinal();
        if (traktTab && !config.hasTrakt()) {
            status.setText("Add your Trakt app's client id and secret with install.sh to see what you are watching."
                    + " See the README.");
            return;
        }
        if (!traktTab && !config.hasTmdb()) {
            status.setText("Add a TMDB API read access token with install.sh to see what is trending. See the README.");
            return;
        }
        if (traktTab && !trakt.signedIn()) {
            signIn();
            return;
        }
        List<Shelf> rows = loaded.get(t);
        Long at = loadedAt.get(t);
        if (rows != null) {
            draw(rows);
            if (at != null && System.currentTimeMillis() - at < FRESH_MS) return;
        } else {
            status.setText("Loading…");
        }
        load(t);
    }

    private void load(Tab t) {
        Config c = config;
        Trakt tr = trakt;
        net.execute(() -> {
            List<Shelf> rows = null;
            String why = null;
            try {
                rows = read(t, c, tr);
            } catch (JSONException e) {
                why = "signed out".equals(e.getMessage()) ? null : String.valueOf(e.getMessage());
            }
            List<Shelf> r = rows;
            String w = why;
            main.post(() -> {
                if (isFinishing()) return;
                if (r == null && w == null) {
                    // Trakt refused the sign-in: start again.
                    if (tab == t) show(t);
                    return;
                }
                if (r != null) {
                    loaded.put(t, r);
                    loadedAt.put(t, System.currentTimeMillis());
                }
                if (tab != t) return;
                if (r != null) draw(r);
                else status.setText(w);
            });
        });
    }

    /** One tab's rows. Blocking. */
    private static List<Shelf> read(Tab t, Config c, Trakt tr) throws JSONException {
        Tmdb tmdb = new Tmdb(c);
        List<Shelf> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        switch (t) {
            case UP_NEXT:
                rows.add(new Shelf("Up next", complete(c, tmdb, tr.upNext())));
                break;
            case PAUSED:
                rows.add(new Shelf("Paused", complete(c, tmdb, tr.paused())));
                break;
            case WATCHLIST:
                rows.add(new Shelf("Films on your watchlist", complete(c, tmdb, tr.watchlist("movies"))));
                rows.add(new Shelf("Series on your watchlist", complete(c, tmdb, tr.watchlist("shows"))));
                break;
            case TRENDING:
                rows.add(new Shelf("Trending this week", tmdb.list(tmdb.trending(), null)));
                for (String r : c.regions) {
                    rows.add(new Shelf("Popular films on streaming · " + country(r), tmdb.list(tmdb.popularStreaming("movie", r), "movie")));
                    rows.add(new Shelf("Popular series on streaming · " + country(r), tmdb.list(tmdb.popularStreaming("tv", r), "tv")));
                }
                break;
            case NEW:
                for (String r : c.regions) {
                    rows.add(new Shelf("New films to stream or rent · " + country(r), tmdb.list(tmdb.newMovies(r, today), "movie")));
                    rows.add(new Shelf("New series · " + country(r), tmdb.list(tmdb.newSeries(r, today), "tv")));
                }
                break;
        }
        List<Shelf> out = new ArrayList<>();
        for (Shelf s : rows) {
            List<Title> unique = dedupe(s.titles);
            if (!unique.isEmpty()) out.add(new Shelf(s.heading, unique));
        }
        return out;
    }

    /** Posters and overviews for Trakt's titles, read from TMDB four at a time. */
    private static List<Title> complete(Config c, Tmdb tmdb, List<Title> titles) {
        if (!c.hasTmdb() || titles.isEmpty()) return titles;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (Title t : titles) jobs.add(pool.submit(() -> tmdb.complete(t, false)));
            for (Future<?> f : jobs) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // That title stays without a poster.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return titles;
    }

    private static List<Title> dedupe(List<Title> titles) {
        Set<String> seen = new LinkedHashSet<>();
        List<Title> out = new ArrayList<>();
        for (Title t : titles) {
            if (seen.add(t.key())) out.add(t);
            if (out.size() == ROW_MAX) break;
        }
        return out;
    }

    /** "US", "UK", or the country's English name ("France"). */
    static String country(String code) {
        if ("US".equals(code)) return "US";
        if ("GB".equals(code)) return "UK";
        String name = new Locale("", code).getDisplayCountry(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }

    private void draw(List<Shelf> rows) {
        content.removeAllViews();
        status.setText(rows.isEmpty() ? "Nothing here yet." : "");
        for (Shelf s : rows) {
            TextView h = new TextView(this);
            h.setText(s.heading);
            h.setTextSize(17);
            h.setTextColor(0xFFFFFFFF);
            h.setPadding(px(12), px(8), 0, 0);
            content.addView(h);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setClipChildren(false);
            // Room for the focused poster, which grows by 6 %, so its frame and caption aren't cut off.
            scroll.setPadding(px(12), px(16), px(48), px(16));
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            for (Title t : s.titles) {
                PosterCard card = new PosterCard(this, t, () -> open(t));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = px(14);
                row.addView(card, lp);
            }
            scroll.addView(row);
            content.addView(scroll);
        }
    }

    private void open(Title t) {
        startActivity(new Intent(this, DetailActivity.class).putExtra(DetailActivity.TITLE, t.toJson()));
    }

    // ---- Trakt sign-in, with the device code

    private void signIn() {
        int generation = ++signInGeneration;
        status.setText("Getting a Trakt sign-in code…");
        Trakt tr = trakt;
        net.execute(() -> {
            Trakt.DeviceCode code = null;
            try {
                code = tr.deviceCode();
            } catch (JSONException e) {
                // Shown below.
            }
            Trakt.DeviceCode c = code;
            main.post(() -> {
                if (isFinishing() || generation != signInGeneration) return;
                if (c == null) {
                    status.setText("Trakt didn't give a sign-in code. Check the client id, or the network.");
                    return;
                }
                drawCode(c);
                long until = System.currentTimeMillis() + c.expiresIn * 1000L;
                main.postDelayed(() -> poll(c, generation, until, c.interval), c.interval * 1000L);
            });
        });
    }

    private void drawCode(Trakt.DeviceCode c) {
        content.removeAllViews();
        status.setText("Sign in to Trakt: on your phone or computer, open the address below and enter the code.");
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, px(24), 0, 0);
        QrView qr = new QrView(this);
        if (qr.setText(c.url)) box.addView(qr, new LinearLayout.LayoutParams(px(180), px(180)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(px(36), 0, 0, 0);
        TextView url = new TextView(this);
        url.setText(c.url.replaceFirst("^https?://", ""));
        url.setTextSize(22);
        url.setTextColor(0xFF9DA1AB);
        TextView code = new TextView(this);
        code.setText(c.userCode);
        code.setTextSize(54);
        code.setTextColor(0xFFFFFFFF);
        code.setLetterSpacing(0.12f);
        TextView wait = new TextView(this);
        wait.setText("This page carries on by itself once you have entered it.");
        wait.setTextSize(14);
        wait.setTextColor(0xFF9DA1AB);
        text.addView(url);
        text.addView(code);
        text.addView(wait);
        box.addView(text);
        content.addView(box);
    }

    private void poll(Trakt.DeviceCode c, int generation, long until, int interval) {
        if (isFinishing() || generation != signInGeneration) return;
        if (System.currentTimeMillis() > until) {
            signIn();
            return;
        }
        Trakt tr = trakt;
        net.execute(() -> {
            Trakt.Poll p;
            try {
                p = tr.poll(c);
            } catch (JSONException e) {
                p = Trakt.Poll.FAILED;
            }
            Trakt.Poll result = p;
            main.post(() -> {
                if (isFinishing() || generation != signInGeneration) return;
                switch (result) {
                    case SIGNED_IN:
                        loaded.clear();
                        loadedAt.clear();
                        show(tab);
                        break;
                    case WAITING:
                        main.postDelayed(() -> poll(c, generation, until, interval), interval * 1000L);
                        break;
                    case SLOW_DOWN:
                        main.postDelayed(() -> poll(c, generation, until, interval + 1), (interval + 1) * 1000L);
                        break;
                    case DENIED:
                        status.setText("The sign-in was declined on Trakt. Open another tab and come back to try again.");
                        content.removeAllViews();
                        break;
                    case EXPIRED:
                        signIn();
                        break;
                    default:
                        status.setText("Trakt refused the sign-in. Check the client id and secret.");
                        content.removeAllViews();
                        break;
                }
            });
        });
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
