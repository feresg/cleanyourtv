package io.github.feresg.hue;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks for Hue Bridges on the home network with mDNS (_hue._tcp) for a few seconds, resolving each one found to
 * its address. Results come back on the main thread. Call stop() when the screen closes.
 */
final class Finder {
    interface Done {
        void found(List<String> hosts);
    }

    private static final String TYPE = "_hue._tcp";
    private static final long SEARCH_MS = 6000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final List<String> hosts = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private boolean running;
    private Done done;

    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        @Override
        public void onServiceFound(NsdServiceInfo info) {
            main.post(() -> {
                toResolve.add(info);
                resolveNext();
            });
        }

        @Override public void onServiceLost(NsdServiceInfo info) { }
        @Override public void onDiscoveryStarted(String type) { }
        @Override public void onDiscoveryStopped(String type) { }

        @Override
        public void onStartDiscoveryFailed(String type, int error) {
            main.post(Finder.this::finish);
        }

        @Override public void onStopDiscoveryFailed(String type, int error) { }
    };

    Finder(Context c) {
        nsd = c.getSystemService(NsdManager.class);
        WifiManager wifi = c.getApplicationContext().getSystemService(WifiManager.class);
        lock = wifi == null ? null : wifi.createMulticastLock("hue-find");
    }

    void start(Done d) {
        if (running) return;
        running = true;
        done = d;
        hosts.clear();
        try {
            if (lock != null) lock.acquire();
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            finish();
            return;
        }
        main.postDelayed(this::finish, SEARCH_MS);
    }

    void stop() {
        done = null;
        finish();
    }

    @SuppressWarnings("deprecation") // resolveService: the replacement needs API 34; this app runs from 26.
    private void resolveNext() {
        if (!running || resolving || toResolve.isEmpty()) return;
        resolving = true;
        try {
            nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int error) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    String h = info.getHost() == null ? null : info.getHost().getHostAddress();
                    main.post(() -> {
                        resolving = false;
                        if (h != null && Hue.validHost(h) && !hosts.contains(h)) hosts.add(h);
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private void finish() {
        main.removeCallbacksAndMessages(null);
        if (!running) return;
        running = false;
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (RuntimeException ignored) {
            // Discovery never started.
        }
        if (lock != null && lock.isHeld()) lock.release();
        Done d = done;
        done = null;
        if (d != null) d.found(new ArrayList<>(hosts));
    }
}
