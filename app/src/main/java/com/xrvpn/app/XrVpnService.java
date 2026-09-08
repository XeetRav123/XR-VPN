package com.xrvpn.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VpnService with split routes + per-app bypass (addDisallowedApplication).
 *
 * IMPORTANT: TUN is real (system VPN icon). Without a remote protocol engine,
 * packets into the TUN are not forwarded — other apps may lose internet in
 * full-tunnel mode. Bypass apps keep direct internet (not in VPN).
 */
public class XrVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.xrvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xrvpn.DISCONNECT";
    public static final String EXTRA_CONFIG = "config_json";

    private static final String TAG = "XrVpn";
    private static final String CH = "xr_vpn_ch";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ParcelFileDescriptor tunFd;

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopTunnel();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            startForeground(42, buildNotification("Connecting…"));
            try {
                openTunnel(intent.getStringExtra(EXTRA_CONFIG));
                startForeground(42, buildNotification("Connected · XR VPN"));
            } catch (Exception e) {
                Log.e(TAG, "tunnel failed", e);
                stopTunnel();
                stopForeground(true);
                stopSelf();
            }
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void openTunnel(String json) throws Exception {
        stopTunnel();
        JSONObject cfg = new JSONObject(json != null ? json : "{}");
        String endpoint = cfg.optString("endpoint", "");

        JSONObject routing = cfg.optJSONObject("routing");
        String mode = routing != null ? routing.optString("mode", "full") : "full";
        List<String> list = new ArrayList<>();
        if (routing != null) {
            JSONArray arr = routing.optJSONArray("list");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) list.add(s);
                }
            }
        }

        List<String> bypassApps = new ArrayList<>();
        JSONArray apps = cfg.optJSONArray("bypassApps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                String pkg = apps.optString(i, "").trim();
                if (!pkg.isEmpty()) bypassApps.add(pkg);
            }
        }

        Builder builder = new Builder();
        builder.setSession("XR VPN");
        builder.setMtu(1280);
        builder.addAddress("10.8.0.2", 32);
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("9.9.9.9");

        // Always exclude self
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {}

        // Apps that must NOT use VPN (YouTube, banking, etc.)
        for (String pkg : bypassApps) {
            try {
                builder.addDisallowedApplication(pkg);
                Log.i(TAG, "bypass app: " + pkg);
            } catch (Exception e) {
                Log.w(TAG, "cannot bypass " + pkg + ": " + e.getMessage());
            }
        }

        applyRoutes(builder, mode, list);

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IllegalStateException("establish() returned null");
        }
        RUNNING.set(true);
        Log.i(TAG, "TUN up mode=" + mode + " bypassApps=" + bypassApps.size());

        final ParcelFileDescriptor local = tunFd;
        new Thread(() -> {
            byte[] buf = new byte[32767];
            try (java.io.FileInputStream in = new java.io.FileInputStream(local.getFileDescriptor())) {
                while (RUNNING.get()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    // TODO: WireGuard/OpenVPN forward — without this, full-tunnel breaks net
                }
            } catch (Exception e) {
                if (RUNNING.get()) Log.w(TAG, "tun read: " + e.getMessage());
            }
        }, "xr-tun-reader").start();
    }

    private void applyRoutes(Builder b, String mode, List<String> list) {
        if ("only".equals(mode)) {
            boolean added = false;
            for (String item : list) {
                if (addOneRoute(b, item)) added = true;
            }
            if (!added) Log.w(TAG, "only-mode: no routes");
            return;
        }
        b.addRoute("0.0.0.0", 0);
        if ("bypass".equals(mode)) {
            for (String item : list) Log.i(TAG, "bypass host: " + item);
        }
    }

    private boolean addOneRoute(Builder b, String item) {
        try {
            if (item.contains("/")) {
                String[] p = item.split("/");
                b.addRoute(p[0], Integer.parseInt(p[1]));
                return true;
            }
            if (item.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                b.addRoute(item, 32);
                return true;
            }
            InetAddress[] addrs = InetAddress.getAllByName(item.replaceFirst("^\\*\\.", ""));
            boolean ok = false;
            for (InetAddress a : addrs) {
                String ip = a.getHostAddress();
                if (ip != null && ip.indexOf(':') < 0) {
                    b.addRoute(ip, 32);
                    ok = true;
                }
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopTunnel() {
        RUNNING.set(false);
        if (tunFd != null) {
            try { tunFd.close(); } catch (Exception ignored) {}
            tunFd = null;
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "XR VPN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return nb.setContentTitle("XR VPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }
}
