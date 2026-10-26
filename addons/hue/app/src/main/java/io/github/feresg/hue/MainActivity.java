package io.github.feresg.hue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tile and module card open this: your Hue rooms and zones. OK switches one on or off, Left and Right dim it by
 * a tenth, Menu (or holding OK) picks one of its scenes. The first time it finds the bridge on the network and
 * pairs with its link button. It talks to the bridge only while this screen is open.
 */
public class MainActivity extends Activity {
    private static final int PANEL_WIDTH_DP = 480;
    private static final long REFRESH_MS = 5000;
    private static final long PAIR_EVERY_MS = 2000;
    private static final int PAIR_TRIES = 15;
    /** Left and Right presses within this time are sent as one change. */
    private static final long DIM_DEBOUNCE_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;
    private Finder finder;
    private String host = "";
    private String user = "";
    private List<Hue.Group> groups = new ArrayList<>();
    private List<String> found = new ArrayList<>();
    private boolean searching;
    private int pairTries;
    private String problem;
    /** Brightness waiting to be sent, by group id: 0 means off. */
    private final Map<String, Integer> pendingBri = new HashMap<>();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };
    private final Runnable pairStep = this::pairOnce;
    private final Runnable sendDims = this::sendDims;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finder = new Finder(this);
        // A panel on the right over Home, opened from the launcher's top bar; Back closes it.
        getWindow().setLayout(px(PANEL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().setGravity(Gravity.END);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundResource(R.drawable.panel);
        page.setPadding(px(32), px(28), px(28), px(20));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTextColor(0xFFFFFFFF);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFFA89F91);
        status.setPadding(0, 0, 0, px(16));
        page.addView(title);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = prefs();
        host = p.getString("host", "");
        user = p.getString("user", "");
        start();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        main.removeCallbacks(pairStep);
        finder.stop();
        searching = false;
        if (!pendingBri.isEmpty()) sendDims();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof Hue.Group) {
                pickScene((Hue.Group) f.getTag());
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("bridge", MODE_PRIVATE);
    }

    /** Whatever comes next: find a bridge, pair with it, or show the rooms. */
    private void start() {
        main.removeCallbacks(refresh);
        main.removeCallbacks(pairStep);
        problem = null;
        if (host.isEmpty()) {
            search();
        } else if (user.isEmpty()) {
            pairTries = 0;
            main.post(pairStep);
        } else {
            main.post(refresh);
        }
        build();
    }

    private void search() {
        searching = true;
        found = new ArrayList<>();
        build();
        finder.start(hosts -> {
            if (!hosts.isEmpty()) {
                searched(hosts);
                return;
            }
            // Not on the network (wired TVs often don't see mDNS): ask Signify's discovery address once.
            net.execute(() -> {
                Bridge.Reply r = Bridge.discover();
                List<String> list = new ArrayList<>();
                if (r.ok()) {
                    try {
                        list = Hue.discovered(r.body);
                    } catch (JSONException ignored) {
                        // Nothing usable.
                    }
                }
                List<String> l = list;
                main.post(() -> searched(l));
            });
        });
    }

    private void searched(List<String> hosts) {
        if (isFinishing()) return;
        searching = false;
        found = hosts;
        if (hosts.size() == 1) {
            choose(hosts.get(0));
            return;
        }
        build();
    }

    private void choose(String h) {
        host = h;
        user = "";
        prefs().edit().putString("host", h).remove("user").apply();
        start();
    }

    private void forget() {
        host = "";
        user = "";
        groups = new ArrayList<>();
        prefs().edit().clear().apply();
        start();
    }

    private void pairOnce() {
        if (pairTries >= PAIR_TRIES) {
            problem = "The link button wasn't pressed in time. Press Pair to try again.";
            build();
            return;
        }
        pairTries++;
        String h = host;
        net.execute(() -> {
            Bridge.Reply r = new Bridge(h, "").pair();
            String name = null;
            String why = null;
            if (r.ok()) {
                try {
                    name = Hue.username(r.body);
                } catch (JSONException e) {
                    why = "The bridge's answer couldn't be read.";
                }
            } else {
                why = r.code == 0 ? "The bridge at " + h + " can't be reached." : "The bridge answered " + r.code + ".";
            }
            String n = name;
            String w = why;
            main.post(() -> {
                if (isFinishing() || !h.equals(host)) return;
                if (n != null) {
                    user = n;
                    prefs().edit().putString("user", n).apply();
                    start();
                    return;
                }
                problem = w;
                build();
                main.postDelayed(pairStep, PAIR_EVERY_MS);
            });
        });
    }

    private void load() {
        if (!pendingBri.isEmpty()) return;
        String h = host, u = user;
        net.execute(() -> {
            Bridge.Reply r = new Bridge(h, u).groups();
            List<Hue.Group> list = null;
            String why = null;
            boolean lost = false;
            if (r.ok()) {
                if (Hue.unauthorized(r.body)) {
                    lost = true;
                } else {
                    try {
                        list = Hue.groups(r.body);
                    } catch (JSONException e) {
                        why = Hue.error(r.body) != null ? Hue.error(r.body) : "The bridge's answer couldn't be read.";
                    }
                }
            } else {
                why = r.code == 0 ? "The bridge at " + h + " can't be reached." : "The bridge answered " + r.code + ".";
            }
            List<Hue.Group> l = list;
            String w = why;
            boolean gone = lost;
            main.post(() -> {
                if (isFinishing() || !h.equals(host)) return;
                if (gone) {
                    // The pairing was deleted in the Hue app: pair again.
                    user = "";
                    prefs().edit().remove("user").apply();
                    start();
                    return;
                }
                problem = w;
                if (l != null) groups = l;
                if (pendingBri.isEmpty()) build();
            });
        });
    }

    private void send(String groupId, String body) {
        String h = host, u = user;
        net.execute(() -> {
            Bridge.Reply r = new Bridge(h, u).action(groupId, body);
            String err = r.ok() ? Hue.error(r.body) : (r.code == 0 ? "The bridge can't be reached." : "The bridge answered " + r.code + ".");
            main.post(() -> {
                if (err != null) status.setText(err);
                main.removeCallbacks(refresh);
                main.postDelayed(refresh, 500);
            });
        });
    }

    private void toggle(Hue.Group g) {
        send(g.id, Hue.on(!g.anyOn));
    }

    private void dim(Hue.Group g, Row row, int direction) {
        Integer pending = pendingBri.get(g.id);
        boolean on = pending != null ? pending > 0 : g.anyOn;
        int bri = pending != null && pending > 0 ? pending : g.bri;
        int next = Hue.step(on, bri, direction);
        pendingBri.put(g.id, next);
        row.setBar(next / (float) Hue.BRI_MAX);
        row.setValue(next <= 0 ? "Off" : Math.max(1, Math.round(next * 100f / Hue.BRI_MAX)) + " %");
        main.removeCallbacks(sendDims);
        main.postDelayed(sendDims, DIM_DEBOUNCE_MS);
    }

    private void sendDims() {
        main.removeCallbacks(sendDims);
        for (Map.Entry<String, Integer> e : pendingBri.entrySet()) send(e.getKey(), Hue.bri(e.getValue()));
        pendingBri.clear();
    }

    private void pickScene(Hue.Group g) {
        String h = host, u = user;
        net.execute(() -> {
            Bridge.Reply r = new Bridge(h, u).scenes();
            List<Hue.Scene> list = new ArrayList<>();
            if (r.ok()) {
                try {
                    list = Hue.scenes(r.body, g);
                } catch (JSONException ignored) {
                    // Shown as no scenes.
                }
            }
            List<Hue.Scene> l = list;
            main.post(() -> {
                if (isFinishing()) return;
                if (l.isEmpty()) {
                    status.setText(g.name + " has no scenes. Make them in the Hue app.");
                    return;
                }
                String[] names = new String[l.size()];
                for (int i = 0; i < names.length; i++) names[i] = l.get(i).name;
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(g.name)
                        .setItems(names, (d, which) -> send(g.id, Hue.scene(l.get(which).id)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    /** Rebuilds the list; focus stays on the row it was on. */
    private void build() {
        int focused = rows.indexOfChild(rows.getFocusedChild());
        rows.removeAllViews();
        Row first = null;
        if (host.isEmpty()) {
            status.setText(searching ? "Looking for your Hue Bridge on the network…"
                    : found.isEmpty() ? "No Hue Bridge found. Is the TV on the same network? Enter its address instead."
                    : "Choose your bridge.");
            for (String h : found) {
                Row r = add(new Row(this, "Hue Bridge", h, false, () -> choose(h), null));
                if (first == null) first = r;
            }
            heading("Bridge");
            Row again = add(new Row(this, "Search again", null, false, this::search, null));
            add(new Row(this, "Enter the bridge's address", "Its IP address, from your router or the Hue app", false,
                    this::enterHost, null));
            if (first == null) first = again;
        } else if (user.isEmpty()) {
            status.setText(problem != null && pairTries >= PAIR_TRIES ? problem
                    : "Press the round link button on top of the bridge at " + host + ". Waiting…");
            first = add(new Row(this, "Pair", "Press the bridge's button first, then this", false, () -> {
                pairTries = 0;
                main.removeCallbacks(pairStep);
                main.post(pairStep);
                build();
            }, null));
            heading("Bridge");
            add(new Row(this, "Use another bridge", host, false, this::forget, null));
        } else {
            status.setText(problem != null ? problem
                    : groups.isEmpty() ? "Reading your rooms…"
                    : "OK switches · ◀ ▶ dims · Menu for scenes");
            first = add(new Row(this, "All off", "Every light on the bridge", false, () -> send("0", Hue.on(false)), null));
            String section = null;
            for (Hue.Group g : groups) {
                if (!g.type.equals(section)) {
                    section = g.type;
                    heading("Room".equals(section) ? "Rooms" : "Zones");
                }
                Row[] holder = new Row[1];
                Row r = add(new Row(this, g.name, null, true, () -> toggle(g), d -> dim(g, holder[0], d)));
                holder[0] = r;
                r.setTag(g);
                r.setLongClickable(true);
                r.setOnLongClickListener(v -> {
                    pickScene(g);
                    return true;
                });
                r.setValue(g.anyOn ? g.percent() + " %" : "Off");
                r.setBar(g.anyOn ? g.bri / (float) Hue.BRI_MAX : 0f);
                if (g.anyOn && !g.allOn) r.setDetail("Some lights on");
            }
            heading("Bridge");
            add(new Row(this, "Use another bridge", host, false, this::forget, null));
        }

        View keep = focused >= 0 && focused < rows.getChildCount() ? rows.getChildAt(focused) : null;
        if (keep != null && keep.isFocusable()) keep.requestFocus();
        else if (first != null) first.requestFocus();
    }

    private void enterHost() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine(true);
        field.setHint("192.168.1.20");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hue Bridge address")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    String h = field.getText().toString().trim().replaceFirst("^https?://", "").replaceFirst("/.*$", "");
                    if (!Hue.validHost(h)) {
                        status.setText("That doesn't look like an address: " + h);
                        return;
                    }
                    choose(h);
                })
                .setNegativeButton("Cancel", null)
                .show();
        field.requestFocus();
    }

    private void heading(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(13);
        h.setTextColor(0xFFFFB648);
        h.setPadding(px(16), px(18), 0, px(4));
        rows.addView(h);
    }

    private Row add(Row r) {
        rows.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
