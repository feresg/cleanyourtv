package io.github.feresg.vpnstatus;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tile and module card open this: whether the TV's traffic goes through NordVPN right now, the public IP and
 * where it appears to be. Quick connect opens the NordVPN app, where its own Connect button does the rest; another
 * app can't start NordVPN's tunnel. Nothing runs while this screen is closed.
 */
public class MainActivity extends Activity {
    static final String NORDVPN = "com.nordvpn.android";
    /** A new network takes a moment to carry traffic; ask NordVPN after it has settled. */
    private static final long SETTLE_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private ConnectivityManager connectivity;
    private TextView state;
    private TextView details;
    private TextView tunnel;
    private TextView note;
    private Button connect;
    private boolean watching;
    private final Runnable refresh = this::refresh;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            later();
        }

        @Override
        public void onLost(Network network) {
            later();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            later();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivity = getSystemService(ConnectivityManager.class);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(0xFF0B1426);
        page.setPadding(px(64), px(40), px(64), px(40));

        TextView title = text(20, 0xFF8B949E);
        title.setText(R.string.app_name);
        state = text(56, 0xFFFFFFFF);
        details = text(22, 0xFFC9D1D9);
        tunnel = text(16, 0xFF8B949E);
        note = text(15, 0xFFF0B429);
        page.addView(title);
        page.addView(state);
        page.addView(details);
        page.addView(tunnel);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, px(32), 0, px(12));
        connect = button("Quick connect", v -> openNordVpn());
        buttons.addView(connect);
        buttons.addView(button("Refresh", v -> refresh()));
        page.addView(buttons);
        page.addView(note);
        setContentView(page);
        connect.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean installed = launchIntent() != null;
        connect.setText(installed ? "Quick connect" : "NordVPN app missing");
        connect.setEnabled(installed);
        note.setText(installed ? "" : "Install or re-enable the NordVPN app (" + NORDVPN + ") to connect from here.");
        try {
            connectivity.registerDefaultNetworkCallback(callback);
            watching = true;
        } catch (RuntimeException e) {
            watching = false;
        }
        refresh();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        if (watching) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            watching = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    /** Network callbacks come in bursts while a tunnel goes up or down; refresh once after the last. */
    private void later() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, SETTLE_MS);
    }

    private void refresh() {
        main.removeCallbacks(refresh);
        boolean vpn = vpnUp();
        tunnel.setText(vpn ? "A VPN tunnel is up on this TV." : "No VPN tunnel on this TV.");
        if (state.getText().length() == 0) {
            state.setText("Checking…");
            state.setTextColor(0xFFFFFFFF);
        }
        net.execute(() -> {
            String[] why = new String[1];
            Insights i = Insights.fetch(why);
            main.post(() -> {
                if (isFinishing()) return;
                show(i, why[0], vpnUp());
            });
        });
    }

    private void show(Insights i, String why, boolean vpn) {
        if (i == null) {
            state.setText(vpn ? "VPN on" : "Unknown");
            state.setTextColor(vpn ? 0xFF3FB950 : 0xFFF0B429);
            details.setText(why);
            return;
        }
        state.setText(i.isProtected ? "Protected" : "Not protected");
        state.setTextColor(i.isProtected ? 0xFF3FB950 : 0xFFF85149);
        StringBuilder d = new StringBuilder(i.ip);
        if (!i.place().isEmpty()) d.append("  ·  ").append(i.place());
        if (!i.isp.isEmpty()) d.append("\n").append(i.isp);
        details.setText(d);
        if (vpn && !i.isProtected) {
            tunnel.setText("A VPN tunnel is up, but this IP isn't a NordVPN server: another VPN, or a split tunnel.");
        }
    }

    private boolean vpnUp() {
        try {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Intent launchIntent() {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(NORDVPN);
        return i != null ? i : pm.getLaunchIntentForPackage(NORDVPN);
    }

    private void openNordVpn() {
        Intent i = launchIntent();
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            note.setText("The NordVPN app wouldn't open.");
        }
    }

    private TextView text(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(4), 0, px(4));
        return t;
    }

    private Button button(String label, android.view.View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(getColorStateList(R.color.button_text));
        b.setBackgroundResource(R.drawable.button);
        b.setPadding(px(28), 0, px(28), 0);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(56));
        lp.leftMargin = lp.rightMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
