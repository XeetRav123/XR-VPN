package com.xrvpn.app;

import android.net.VpnService;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Userspace TCP: fake TCP on TUN side, real Socket (protect) on network side.
 * Direct path — apps get internet after Connect (exit IP = phone ISP).
 * Worker path was unstable; optional later.
 */
public class TcpForwarder {

    private static final String TAG = "TcpForwarder";
    private static final AtomicLong ISN_CTR = new AtomicLong(
            (System.currentTimeMillis() / 1000L) << 10 & 0xFFFFFFFFL);

    private final VpnService svc;
    private final PacketForwarder fwd;
    private final Map<String, TcpSession> table = new ConcurrentHashMap<>();

    TcpForwarder(VpnService svc, PacketForwarder fwd) {
        this.svc = svc;
        this.fwd = fwd;
    }

    void handle(byte[] pkt, int ihl, byte[] srcIP, byte[] dstIP) {
        int srcPort = IpUtil.tcpSrcPort(pkt, ihl);
        int dstPort = IpUtil.tcpDstPort(pkt, ihl);
        int flags = IpUtil.tcpFlags(pkt, ihl);
        long theirSeq = IpUtil.tcpSeq(pkt, ihl);
        String key = key(srcIP, srcPort, dstIP, dstPort);

        if ((flags & IpUtil.RST) != 0) {
            TcpSession s = table.remove(key);
            if (s != null) s.close();
            return;
        }

        // SYN — open real socket + SYN-ACK to app
        if ((flags & IpUtil.SYN) != 0 && (flags & IpUtil.ACK) == 0) {
            TcpSession old = table.remove(key);
            if (old != null) old.close();

            long myISN = ISN_CTR.getAndAdd(128) & 0xFFFFFFFFL;
            TcpSession s = new TcpSession(srcIP, srcPort, dstIP, dstPort,
                    (theirSeq + 1) & 0xFFFFFFFFL,
                    (myISN + 1) & 0xFFFFFFFFL);
            table.put(key, s);

            // SYN-ACK immediately so app proceeds
            toTun(s, IpUtil.SYN | IpUtil.ACK, myISN, s.peerNextSeq, null);
            new Thread(() -> relayDirect(s, key), "tcp-direct").start();
            return;
        }

        TcpSession s = table.get(key);
        if (s == null) {
            fwd.writeToTun(IpUtil.tcpPacket(dstIP, dstPort, srcIP, srcPort,
                    0L, (theirSeq + 1) & 0xFFFFFFFFL, IpUtil.RST | IpUtil.ACK, null));
            return;
        }

        if ((flags & IpUtil.FIN) != 0) {
            s.peerNextSeq = (theirSeq + 1) & 0xFFFFFFFFL;
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
            s.halfClose();
            return;
        }

        int dataOff = IpUtil.tcpDataOff(pkt, ihl);
        int dataLen = pkt.length - dataOff;
        if (dataLen > 0) {
            s.peerNextSeq = (theirSeq + dataLen) & 0xFFFFFFFFL;
            s.enqueue(Arrays.copyOfRange(pkt, dataOff, dataOff + dataLen));
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
        } else if ((flags & IpUtil.ACK) != 0) {
            // pure ACK — ignore
        }
    }

    /** Real TCP via protected socket (internet works, no Worker). */
    private void relayDirect(TcpSession s, String key) {
        Socket sock = null;
        try {
            sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(0);
            boolean protectedOk = svc.protect(sock);
            Log.i(TAG, "protect=" + protectedOk + " → " + ip(s.dstIP) + ":" + s.dstPort);

            sock.connect(new InetSocketAddress(
                    InetAddress.getByAddress(s.dstIP), s.dstPort), 12_000);
            s.socket = sock;

            final Socket finalSock = sock;
            final OutputStream out = sock.getOutputStream();
            final InputStream in = sock.getInputStream();

            // TUN → network
            new Thread(() -> {
                try {
                    while (true) {
                        byte[] chunk = s.dequeue();
                        if (chunk == null || (chunk.length == 0 && s.halfClosed)) break;
                        if (chunk.length > 0) {
                            out.write(chunk);
                            out.flush();
                        }
                    }
                    try { sock.shutdownOutput(); } catch (Exception ignored) {}
                } catch (Exception e) {
                    Log.w(TAG, "tx: " + e.getMessage());
                    s.close();
                }
            }, "tcp-tx").start();

            // network → TUN
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                byte[] data = Arrays.copyOf(buf, n);
                toTun(s, IpUtil.PSH | IpUtil.ACK, s.myNextSeq, s.peerNextSeq, data);
                s.myNextSeq = (s.myNextSeq + data.length) & 0xFFFFFFFFL;
            }

            toTun(s, IpUtil.FIN | IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
            s.myNextSeq = (s.myNextSeq + 1) & 0xFFFFFFFFL;

        } catch (Exception e) {
            Log.e(TAG, "relay fail " + ip(s.dstIP) + ":" + s.dstPort + " " + e.getMessage());
            if (!s.closed) {
                fwd.writeToTun(IpUtil.tcpPacket(s.dstIP, s.dstPort, s.srcIP, s.srcPort,
                        s.myNextSeq, s.peerNextSeq, IpUtil.RST | IpUtil.ACK, null));
            }
        } finally {
            table.remove(key);
            s.close();
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        }
    }

    private void toTun(TcpSession s, int flags, long seq, long ack, byte[] data) {
        fwd.writeToTun(IpUtil.tcpPacket(
                s.dstIP, s.dstPort,
                s.srcIP, s.srcPort,
                seq, ack, flags, data));
    }

    void closeAll() {
        table.values().forEach(TcpSession::close);
        table.clear();
    }

    private static String key(byte[] si, int sp, byte[] di, int dp) {
        return ip(si) + ":" + sp + ">" + ip(di) + ":" + dp;
    }

    static String ip(byte[] a) {
        return (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF);
    }

    static class TcpSession {
        final byte[] srcIP, dstIP;
        final int srcPort, dstPort;
        volatile long peerNextSeq, myNextSeq;
        volatile Socket socket;
        volatile boolean closed, halfClosed;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        TcpSession(byte[] srcIP, int srcPort, byte[] dstIP, int dstPort,
                   long peerNextSeq, long myNextSeq) {
            this.srcIP = srcIP;
            this.srcPort = srcPort;
            this.dstIP = dstIP;
            this.dstPort = dstPort;
            this.peerNextSeq = peerNextSeq;
            this.myNextSeq = myNextSeq;
        }

        void enqueue(byte[] data) {
            if (!closed) queue.offer(data);
        }

        byte[] dequeue() {
            try {
                return queue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        }

        void halfClose() {
            halfClosed = true;
            queue.offer(new byte[0]);
        }

        void close() {
            closed = true;
            halfClosed = true;
            queue.offer(new byte[0]);
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
        }
    }
}
