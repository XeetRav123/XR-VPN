package com.xrvpn.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

public class XrVpnService extends VpnService {

    public static final String ACTION_CONNECT    = "com.xrvpn.app.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xrvpn.app.DISCONNECT";
    public static final String EXTRA_CONFIG      = "config";

    private static final String CHANNEL_ID  = "xrvpn_channel";
    private static final int    NOTIF_ID    = 1;
    private static final long   RECONNECT_DELAY_MS = 5_000; // 5 сек между попытками

    private static volatile boolean running = false;
    public static boolean isRunning() { return running; }

    private ParcelFileDescriptor tun;
    private PacketForwarder      forwarder;
    private Thread               watchdog;
    private volatile boolean     shouldRun = false;
    private String               lastConfig;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        if (ACTION_CONNECT.equals(intent.getAction())) {
            lastConfig = intent.getStringExtra(EXTRA_CONFIG);
            shouldRun  = true;
            startForegroundNotification();
            startWatchdog();
        } else {
            shouldRun = false;
            disconnect();
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shouldRun = false;
        disconnect();
        super.onDestroy();
    }

    // ── Watchdog — следит за соединением и переподключает ─────────────────

    private void startWatchdog() {
        if (watchdog != null && watchdog.isAlive()) return;

        watchdog = new Thread(() -> {
            while (shouldRun) {
                try {
                    connect(lastConfig);

                    // Ждём пока форвардер жив
                    while (shouldRun && forwarder != null && !forwarder.isStopped()) {
                        Thread.sleep(1_000);
                    }

                    if (!shouldRun) break;

                    // Форвардер упал — чистим и ждём перед переподключением
                    disconnectInternal();
                    running = false;
                    updateNotification("Переподключение...");
                    Thread.sleep(RECONNECT_DELAY_MS);

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ie) { break; }
                }
            }
        }, "xr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ── Connect / Disconnect ───────────────────────────────────────────────

    private void connect(String json) {
        disconnectInternal();
        try {
            JSONObject cfg     = json != null ? new JSONObject(json) : new JSONObject();
            JSONObject routing = cfg.optJSONObject("routing");
            String     mode    = routing != null ? routing.optString("mode", "full") : "full";
            JSONArray  list    = routing != null ? routing.optJSONArray("list") : null;

            Builder b = new Builder()
                    .setSession("XR VPN")
                    .setMtu(1500)
                    .addAddress("10.111.0.1", 30)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName());

            switch (mode) {
                case "only":
                    if (list != null)
                        for (int i = 0; i < list.length(); i++) addCidr(b, list.optString(i));
                    break;
                default:
                    b.addRoute("0.0.0.0", 0);
                    break;
            }

            tun = b.establish();
            if (tun == null) return;

            forwarder = new PacketForwarder(this, tun, mode, list);
            new Thread(forwarder, "xr-forwarder").start();

            running = true;
            updateNotification("Подключено");

        } catch (Exception e) {
            e.printStackTrace();
            disconnectInternal();
        }
    }

    private void disconnectInternal() {
        if (forwarder != null) { forwarder.stop(); forwarder = null; }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
        running = false;
    }

    public void disconnect() {
        shouldRun = false;
        if (watchdog != null) { watchdog.interrupt(); watchdog = null; }
        disconnectInternal();
    }

    // ── Foreground notification ────────────────────────────────────────────

    private void startForegroundNotification() {
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Подключено"));
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, XrVpnService.class);
        stopIntent.setAction(ACTION_DISCONNECT);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("XR VPN")
                .setContentText(text)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Отключить", stopPi);

        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "XR VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Статус VPN соединения");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void addCidr(Builder b, String cidr) {
        if (cidr == null || cidr.isEmpty()) return;
        try {
            String[] p = cidr.contains("/") ? cidr.split("/") : new String[]{cidr, "32"};
            b.addRoute(p[0].trim(), Integer.parseInt(p[1].trim()));
        } catch (Exception ignored) {}
    }
}
