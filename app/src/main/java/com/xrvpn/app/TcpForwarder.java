package com.xeetr.xrvpn;

import android.net.VpnService;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles TCP packets from the TUN interface using a simplified proxy approach:
 *
 *  1. SYN arrives  → send SYN-ACK, open a real protected Socket to the destination
 *  2. DATA arrives → queue it; background thread flushes to the real socket
 *  3. Real socket receives data → wrap in TCP segments, write to TUN
 *  4. FIN / RST    → tear down
 *
 * No congestion control, no retransmit — suitable for a local transparent proxy.
 */
public class TcpForwarder {

    // ISN increases monotonically to avoid collisions with recently closed sessions
    private static final AtomicLong ISN_CTR = new AtomicLong(
            (System.currentTimeMillis() / 1000L) << 10 & 0xFFFFFFFFL);

    private final VpnService      svc;
    private final PacketForwarder fwd;
    private final Map<String, TcpSession> table = new ConcurrentHashMap<>();

    TcpForwarder(VpnService svc, PacketForwarder fwd) {
        this.svc = svc; this.fwd = fwd;
    }

    // Called from the single forwarder thread — must be fast
    void handle(byte[] pkt, int ihl, byte[] srcIP, byte[] dstIP) {
        int  srcPort   = IpUtil.tcpSrcPort(pkt, ihl);
        int  dstPort   = IpUtil.tcpDstPort(pkt, ihl);
        int  flags     = IpUtil.tcpFlags(pkt, ihl);
        long theirSeq  = IpUtil.tcpSeq(pkt, ihl);
        String key     = key(srcIP, srcPort, dstIP, dstPort);

        // ── RST ──────────────────────────────────────────────────────────
        if ((flags & IpUtil.RST) != 0) {
            TcpSession s = table.remove(key);
            if (s != null) s.close();
            return;
        }

        // ── SYN (new connection, no ACK) ──────────────────────────────────
        if ((flags & IpUtil.SYN) != 0 && (flags & IpUtil.ACK) == 0) {
            TcpSession old = table.remove(key);
            if (old != null) old.close();

            long myISN = ISN_CTR.getAndAdd(128) & 0xFFFFFFFFL;
            // After SYN:      peerNextSeq = theirSeq + 1  (SYN consumes one seq)
            // After SYN-ACK:  myNextSeq   = myISN   + 1
            TcpSession s = new TcpSession(srcIP, srcPort, dstIP, dstPort,
                    (theirSeq + 1) & 0xFFFFFFFFL,
                    (myISN   + 1) & 0xFFFFFFFFL);
            table.put(key, s);

            // SYN-ACK: seq = myISN, ack = theirSeq + 1
            toTun(s, IpUtil.SYN | IpUtil.ACK, myISN, s.peerNextSeq, null);

            new Thread(() -> relay(s, key), "tcp-relay").start();
            return;
        }

        TcpSession s = table.get(key);
        if (s == null) {
            // Unknown session — send RST
            fwd.writeToTun(IpUtil.tcpPacket(dstIP, dstPort, srcIP, srcPort,
                    0L, (theirSeq + 1) & 0xFFFFFFFFL, IpUtil.RST | IpUtil.ACK, null));
            return;
        }

        // ── FIN ───────────────────────────────────────────────────────────
        if ((flags & IpUtil.FIN) != 0) {
            s.peerNextSeq = (theirSeq + 1) & 0xFFFFFFFFL;
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
            s.halfClose();   // signals the TUN→server thread to shut down
            return;
        }

        // ── Data (or bare ACK) ────────────────────────────────────────────
        int dataOff = IpUtil.tcpDataOff(pkt, ihl);
        int dataLen = pkt.length - dataOff;
        if (dataLen > 0) {
            s.peerNextSeq = (theirSeq + dataLen) & 0xFFFFFFFFL;
            s.enqueue(pkt, dataOff, dataLen);
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
        }
    }

    /** Background: connect to real server and relay in both directions. */
    private void relay(TcpSession s, String key) {
        Socket sock = null;
        try {
            sock = new Socket();
            svc.protect(sock);
            sock.connect(new InetSocketAddress(
                    InetAddress.getByAddress(s.dstIP), s.dstPort), 10_000);
            sock.setTcpNoDelay(true);
            s.realSock = sock;

            final Socket finalSock = sock;
            final OutputStream out = sock.getOutputStream();

            // TUN → real server
            new Thread(() -> {
                try {
                    while (true) {
                        byte[] chunk = s.dequeue();
                        if (chunk == null || (chunk.length == 0 && s.halfClosed)) {
                            finalSock.shutdownOutput();
                            break;
                        }
                        if (chunk.length > 0) { out.write(chunk); out.flush(); }
                    }
                } catch (Exception e) { s.close(); }
            }, "tcp-tx").start();

            // Real server → TUN
            InputStream in  = sock.getInputStream();
            byte[] buf      = new byte[PacketForwarder.MTU - 40];
            int n;
            while ((n = in.read(buf)) != -1) {
                byte[] data = Arrays.copyOf(buf, n);
                toTun(s, IpUtil.PSH | IpUtil.ACK, s.myNextSeq, s.peerNextSeq, data);
                s.myNextSeq = (s.myNextSeq + n) & 0xFFFFFFFFL;
            }

            // Server closed connection — send FIN to app
            toTun(s, IpUtil.FIN | IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
            s.myNextSeq = (s.myNextSeq + 1) & 0xFFFFFFFFL;

        } catch (Exception e) {
            if (!s.closed) {
                fwd.writeToTun(IpUtil.tcpPacket(s.dstIP, s.dstPort, s.srcIP, s.srcPort,
                        s.myNextSeq, s.peerNextSeq, IpUtil.RST, null));
            }
        } finally {
            table.remove(key);
            s.close();
        }
    }

    private void toTun(TcpSession s, int flags, long seq, long ack, byte[] data) {
        fwd.writeToTun(IpUtil.tcpPacket(
                s.dstIP, s.dstPort,   // from: server side (we pretend to be the server)
                s.srcIP, s.srcPort,   // to:   app
                seq, ack, flags, data));
    }

    void closeAll() { table.values().forEach(TcpSession::close); table.clear(); }

    private static String key(byte[] si, int sp, byte[] di, int dp) {
        return UdpForwarder.ip(si)+":"+sp+">"+UdpForwarder.ip(di)+":"+dp;
    }

    // ── Session ───────────────────────────────────────────────────────────
    static class TcpSession {
        final byte[] srcIP, dstIP;
        final int    srcPort, dstPort;

        volatile long   peerNextSeq;   // goes into our ACK field
        volatile long   myNextSeq;     // our outgoing SEQ
        volatile Socket realSock;
        volatile boolean closed, halfClosed;

        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        TcpSession(byte[] srcIP, int srcPort, byte[] dstIP, int dstPort,
                   long peerNextSeq, long myNextSeq) {
            this.srcIP = srcIP; this.srcPort = srcPort;
            this.dstIP = dstIP; this.dstPort = dstPort;
            this.peerNextSeq = peerNextSeq;
            this.myNextSeq   = myNextSeq;
        }

        void enqueue(byte[] pkt, int off, int len) {
            if (!closed) queue.offer(Arrays.copyOfRange(pkt, off, off + len));
        }

        byte[] dequeue() {
            try { return queue.poll(30, TimeUnit.SECONDS); }
            catch (InterruptedException e) { return null; }
        }

        void halfClose() {
            halfClosed = true;
            queue.offer(new byte[0]); // wake up the tx thread
        }

        void close() {
            closed = true;
            halfClosed = true;
            queue.offer(new byte[0]);
            try { if (realSock != null) realSock.close(); } catch (Exception ignored) {}
        }
    }
}
