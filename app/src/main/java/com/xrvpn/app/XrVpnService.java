package com.xeetr.xrvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * XR VPN Service — receives connect/disconnect commands from MainActivity
 * (via Intent) or from the JS bridge (XRVpn.connect / XRVpn.disconnect).
 *
 * On connect, a TUN interface is established and a PacketForwarder thread
 * starts reading raw IP packets, forwarding them through real (protected)
 * sockets, and writing responses back into the TUN.
 */
public class XrVpnService extends VpnService {

    public static final String ACTION_CONNECT    = "com.xeetr.xrvpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.xeetr.xrvpn.DISCONNECT";
    public static final String EXTRA_CONFIG      = "config";

    // TUN address — the VPN's virtual local IP
    private static final String TUN_ADDR   = "10.111.0.1";
    private static final int    TUN_PREFIX = 30;

    private static volatile boolean running = false;

    /** Вызывается из MainActivity для проверки статуса. */
    public static boolean isRunning() { return running; }

    private ParcelFileDescriptor tun;
    private PacketForwarder      forwarder;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CONNECT.equals(intent.getAction())) {
            connect(intent.getStringExtra(EXTRA_CONFIG));
        } else {
            disconnect();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    // ── Connect ────────────────────────────────────────────────────────────

    private void connect(String configJson) {
        disconnect(); // tear down any previous session

        try {
            JSONObject cfg     = configJson != null
                    ? new JSONObject(configJson) : new JSONObject();
            JSONObject routing = cfg.optJSONObject("routing");
            String     mode    = routing != null
                    ? routing.optString("mode", "full") : "full";
            JSONArray  list    = routing != null
                    ? routing.optJSONArray("list") : null;

            Builder b = new Builder()
                    .setSession("XR VPN")
                    .setMtu(1500)
                    .addAddress(TUN_ADDR, TUN_PREFIX)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    // Exclude our own app to prevent routing loops
                    .addDisallowedApplication(getPackageName());

            switch (mode) {
                case "only":
                    // Route only the IPs / CIDRs in the list
                    if (list != null)
                        for (int i = 0; i < list.length(); i++)
                            addCidr(b, list.optString(i));
                    break;

                case "bypass":
                    // Route everything; forwarder can skip bypassed IPs if needed
                    b.addRoute("0.0.0.0", 0);
                    break;

                case "full":
                default:
                    b.addRoute("0.0.0.0", 0);
                    break;
            }

            tun = b.establish();
            if (tun == null) return; // user denied permission

            forwarder = new PacketForwarder(this, tun, mode, list);
            new Thread(forwarder, "xr-forwarder").start();
            running = true;

        } catch (Exception e) {
            e.printStackTrace();
            disconnect();
        }
    }

    // ── Disconnect ─────────────────────────────────────────────────────────

    public void disconnect() {
        running = false;
        if (forwarder != null) { forwarder.stop(); forwarder = null; }
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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
}
