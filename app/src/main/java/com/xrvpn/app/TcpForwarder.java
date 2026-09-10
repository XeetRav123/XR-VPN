package com.xrvpn.app;

import android.net.VpnService;
import android.util.Log;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP via Cloudflare Worker WebSocket proxy (foreign exit).
 * Order: open WS+remote FIRST, then SYN-ACK to app (critical for HTTPS).
 */
public class TcpForwarder {

    private static final String TAG = "TcpForwarder";
    private static final AtomicLong ISN = new AtomicLong(
            (System.currentTimeMillis() / 1000L) << 10);

    private final VpnService svc;
    private final PacketForwarder fwd;
    private final Map<String, Session> table = new ConcurrentHashMap<>();

    TcpForwarder(VpnService svc, PacketForwarder fwd) {
        this.svc = svc;
        this.fwd = fwd;
    }

    void handle(byte[] pkt, int ihl, byte[] srcIP, byte[] dstIP) {
        int sp = IpUtil.tcpSrcPort(pkt, ihl);
        int dp = IpUtil.tcpDstPort(pkt, ihl);
        int flags = IpUtil.tcpFlags(pkt, ihl);
        long seq = IpUtil.tcpSeq(pkt, ihl);
        String key = key(srcIP, sp, dstIP, dp);

        if ((flags & IpUtil.RST) != 0) {
            Session s = table.remove(key);
            if (s != null) s.close();
            return;
        }

        // New connection
        if ((flags & IpUtil.SYN) != 0 && (flags & IpUtil.ACK) == 0) {
            Session old = table.remove(key);
            if (old != null) old.close();

            long myIsn = ISN.getAndAdd(64) & 0xffffffffL;
            Session s = new Session(srcIP, sp, dstIP, dp,
                    (seq + 1) & 0xffffffffL, (myIsn + 1) & 0xffffffffL);
            table.put(key, s);

            // Connect Worker BEFORE answering app
            new Thread(() -> openAndRelay(s, key, myIsn), "tcp-open").start();
            return;
        }

        Session s = table.get(key);
        if (s == null) {
            rst(dstIP, dp, srcIP, sp, seq);
            return;
        }

        if ((flags & IpUtil.FIN) != 0) {
            s.peerSeq = (seq + 1) & 0xffffffffL;
            toTun(s, IpUtil.ACK, s.mySeq, s.peerSeq, null);
            s.halfClose = true;
            s.q.offer(new byte[0]);
            return;
        }

        int off = IpUtil.tcpDataOff(pkt, ihl);
        int len = pkt.length - off;
        if (len > 0 && s.ready) {
            s.peerSeq = (seq + len) & 0xffffffffL;
            s.q.offer(Arrays.copyOfRange(pkt, off, off + len));
            toTun(s, IpUtil.ACK, s.mySeq, s.peerSeq, null);
        }
    }

    private void openAndRelay(Session s, String key, long myIsn) {
        WsClient ws = new WsClient();
        try {
            String host = ip(s.dstIP);
            // Skip Cloudflare IPs — Worker cannot TCP to CF ranges
            if (isCloudflareIp(s.dstIP)) {
                Log.w(TAG, "skip CF IP " + host);
                rst(s.dstIP, s.dstPort, s.srcIP, s.srcPort, s.peerSeq - 1);
                table.remove(key);
                return;
            }

            Log.i(TAG, "WS open " + host + ":" + s.dstPort);
            ws.connect(svc, host, s.dstPort);
            s.ws = ws;
            s.ready = true;

            // Now tell the app connection is up
            toTun(s, IpUtil.SYN | IpUtil.ACK, myIsn, s.peerSeq, null);
            Log.i(TAG, "SYN-ACK sent, tunnel ready " + host + ":" + s.dstPort);

            // TUN → Worker
            new Thread(() -> {
                try {
                    while (true) {
                        byte[] chunk = s.q.poll(60, TimeUnit.SECONDS);
                        if (chunk == null) continue;
                        if (chunk.length == 0 && s.halfClose) break;
                        if (chunk.length > 0) ws.send(chunk);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "tx " + e.getMessage());
                } finally {
                    s.close();
                }
            }, "tcp-tx").start();

            // Worker → TUN
            byte[] data;
            while ((data = ws.recv()) != null) {
                if (data.length == 0) continue;
                toTun(s, IpUtil.PSH | IpUtil.ACK, s.mySeq, s.peerSeq, data);
                s.mySeq = (s.mySeq + data.length) & 0xffffffffL;
            }
            toTun(s, IpUtil.FIN | IpUtil.ACK, s.mySeq, s.peerSeq, null);
            s.mySeq = (s.mySeq + 1) & 0xffffffffL;

        } catch (Exception e) {
            Log.e(TAG, "open fail " + ip(s.dstIP) + ":" + s.dstPort + " → " + e.getMessage());
            if (!s.ready) {
                rst(s.dstIP, s.dstPort, s.srcIP, s.srcPort, s.peerSeq - 1);
            } else {
                toTun(s, IpUtil.RST | IpUtil.ACK, s.mySeq, s.peerSeq, null);
            }
        } finally {
            table.remove(key);
            s.close();
            ws.close();
        }
    }

    private void toTun(Session s, int flags, long seq, long ack, byte[] data) {
        fwd.writeToTun(IpUtil.tcpPacket(
                s.dstIP, s.dstPort, s.srcIP, s.srcPort, seq, ack, flags, data));
    }

    private void rst(byte[] fromIP, int fromPort, byte[] toIP, int toPort, long ack) {
        long a = ack & 0xffffffffL;
        if (a == 0xffffffffL) a = 0; // safety
        fwd.writeToTun(IpUtil.tcpPacket(fromIP, fromPort, toIP, toPort,
                0, (a + 1) & 0xffffffffL, IpUtil.RST | IpUtil.ACK, null));
    }

    void closeAll() {
        for (Session s : table.values()) s.close();
        table.clear();
    }

    /** Rough CF ranges that Workers cannot dial */
    private static boolean isCloudflareIp(byte[] ip) {
        int a = ip[0] & 0xff, b = ip[1] & 0xff;
        // 1.0.0.0/8 and 1.1.1.0 style — 1.1.1.1
        if (a == 1 && b == 1) return true;
        if (a == 1 && b == 0) return true;
        // 104.16–104.31, 172.64–172.71, 162.158–162.159, 188.114, 190.93, 197.234, 198.41
        if (a == 104 && b >= 16 && b <= 31) return true;
        if (a == 172 && b >= 64 && b <= 71) return true;
        if (a == 162 && (b == 158 || b == 159)) return true;
        if (a == 188 && b == 114) return true;
        if (a == 198 && b == 41) return true;
        return false;
    }

    private static String key(byte[] si, int sp, byte[] di, int dp) {
        return ip(si) + ":" + sp + ">" + ip(di) + ":" + dp;
    }

    static String ip(byte[] a) {
        return (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + "." + (a[3] & 0xff);
    }

    static class Session {
        final byte[] srcIP, dstIP;
        final int srcPort, dstPort;
        volatile long peerSeq, mySeq;
        volatile boolean ready, halfClose, closed;
        volatile WsClient ws;
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();

        Session(byte[] s, int sp, byte[] d, int dp, long peer, long my) {
            srcIP = s; srcPort = sp; dstIP = d; dstPort = dp;
            peerSeq = peer; mySeq = my;
        }

        void close() {
            closed = true;
            halfClose = true;
            q.offer(new byte[0]);
            try { if (ws != null) ws.close(); } catch (Exception ignored) {}
        }
    }
}
