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

public class XrVpnService extends VpnService {

    public static final String ACTION_CONNECT = "com.xeetr.xrvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xeetr.xrvpn.DISCONNECT";
    public static final String EXTRA_CONFIG = "config";

    private static final String TAG = "XrVpnService";
    private static final String CH = "xr_vpn";
    private static final int NID = 42;

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
        startForeground(NID, notif("Starting…"));

        if (ACTION_CONNECT.equals(intent.getAction())) {
            boolean ok = connect(intent.getStringExtra(EXTRA_CONFIG));
            if (ok) {
                startForeground(NID, notif("Connected · CF Worker exit"));
                return START_STICKY;
            }
            startForeground(NID, notif("Failed"));
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        disconnect();
        stopForeground(true);
        stopSelf();
        return START_NOT_STICKY;
    }

    private boolean connect(String json) {
        disconnect();
        try {
            JSONObject cfg = json != null ? new JSONObject(json) : new JSONObject();
            String wh = cfg.optString("workerHost", "").trim();
            if (!wh.isEmpty()) {
                wh = wh.replace("https://", "").replace("http://", "");
                int s = wh.indexOf('/');
                if (s >= 0) wh = wh.substring(0, s);
                WsClient.WORKER_HOST = wh;
            }
            Log.i(TAG, "worker=" + WsClient.WORKER_HOST);

            Builder b = new Builder()
                    .setSession("XR VPN")
                    .setMtu(1500)
                    .addAddress("10.111.0.1", 30)
                    // Non-Cloudflare DNS (Worker cannot dial 1.1.1.1)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("9.9.9.9")
                    .addRoute("0.0.0.0", 0)
                    .addDisallowedApplication(getPackageName());

            JSONArray apps = cfg.optJSONArray("bypassApps");
            if (apps != null) {
                for (int i = 0; i < apps.length(); i++) {
                    String p = apps.optString(i, "").trim();
                    if (p.isEmpty()) continue;
                    try { b.addDisallowedApplication(p); } catch (Exception ignored) {}
                }
            }

            tun = b.establish();
            if (tun == null) return false;

            JSONObject routing = cfg.optJSONObject("routing");
            String mode = routing != null ? routing.optString("mode", "full") : "full";
            JSONArray list = routing != null ? routing.optJSONArray("list") : null;

            forwarder = new PacketForwarder(this, tun, mode, list);
            new Thread(forwarder, "xr-fwd").start();
            running = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "connect", e);
            disconnect();
            return false;
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

    @Override public void onDestroy() { disconnect(); super.onDestroy(); }
    @Override public void onRevoke() {
        disconnect();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    private Notification notif(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "XR VPN",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, f);
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
