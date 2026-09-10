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

/**
 * TUN + PacketForwarder (TCP via Cloudflare Worker, UDP direct+protect).
 * Must run as foreground service on Android 8+.
 */
public class XrVpnService extends VpnService {

    public static final String ACTION_CONNECT    = "com.xeetr.xrvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xeetr.xrvpn.DISCONNECT";
    public static final String EXTRA_CONFIG      = "config";

    private static final String TAG = "XrVpnService";
    private static final String CH  = "xr_vpn_channel";
    private static final String TUN_ADDR = "10.111.0.1";
    private static final int TUN_PREFIX = 30;
    private static final int NOTIF_ID = 42;

    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    private ParcelFileDescriptor tun;
    private PacketForwarder forwarder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Always promote to foreground first (Android 8+)
        startForeground(NOTIF_ID, buildNotification("Starting…"));

        if (ACTION_CONNECT.equals(intent.getAction())) {
            connect(intent.getStringExtra(EXTRA_CONFIG));
            if (running) {
                startForeground(NOTIF_ID, buildNotification("Connected · Worker tunnel"));
            } else {
                startForeground(NOTIF_ID, buildNotification("Connect failed"));
                stopForeground(true);
                stopSelf();
            }
            return START_STICKY;
        }

        disconnect();
        stopForeground(true);
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        disconnect();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    private void connect(String configJson) {
        disconnect();
        try {
            JSONObject cfg = configJson != null ? new JSONObject(configJson) : new JSONObject();
            JSONObject routing = cfg.optJSONObject("routing");
            String mode = routing != null ? routing.optString("mode", "full") : "full";
            JSONArray list = routing != null ? routing.optJSONArray("list") : null;

            // Optional worker host override from UI/config
            String workerHost = cfg.optString("workerHost", "").trim();
            if (!workerHost.isEmpty()) {
                // strip scheme/path if user pasted full URL
                workerHost = workerHost.replace("https://", "").replace("http://", "");
                int slash = workerHost.indexOf('/');
                if (slash >= 0) workerHost = workerHost.substring(0, slash);
                WsClient.WORKER_HOST = workerHost;
                Log.i(TAG, "Worker host = " + WsClient.WORKER_HOST);
            }

            Builder b = new Builder()
                    .setSession("XR VPN")
                    .setMtu(1500)
                    .addAddress(TUN_ADDR, TUN_PREFIX)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName());

            // Optional per-app bypass
            JSONArray bypassApps = cfg.optJSONArray("bypassApps");
            if (bypassApps != null) {
                for (int i = 0; i < bypassApps.length(); i++) {
                    String pkg = bypassApps.optString(i, "").trim();
                    if (pkg.isEmpty()) continue;
                    try {
                        b.addDisallowedApplication(pkg);
                    } catch (Exception e) {
                        Log.w(TAG, "bypass app fail: " + pkg);
                    }
                }
            }

            switch (mode) {
                case "only":
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) addCidr(b, list.optString(i));
                    }
                    break;
                case "bypass":
                case "full":
                default:
                    b.addRoute("0.0.0.0", 0);
                    break;
            }

            tun = b.establish();
            if (tun == null) {
                Log.e(TAG, "establish() returned null");
                running = false;
                return;
            }

            forwarder = new PacketForwarder(this, tun, mode, list);
            new Thread(forwarder, "xr-forwarder").start();
            running = true;
            Log.i(TAG, "TUN up, forwarder started, worker=" + WsClient.WORKER_HOST);

        } catch (Exception e) {
            Log.e(TAG, "connect error", e);
            disconnect();
        }
    }

    public void disconnect() {
        running = false;
        if (forwarder != null) {
            forwarder.stop();
            forwarder = null;
        }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
    }

    private static void addCidr(Builder b, String cidr) {
        if (cidr == null || cidr.isEmpty()) return;
        try {
            if (cidr.contains("/")) {
                String[] p = cidr.split("/");
                b.addRoute(p[0].trim(), Integer.parseInt(p[1].trim()));
            } else {
                b.addRoute(cidr.trim(), 32);
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "XR VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("VPN tunnel status");
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
}
