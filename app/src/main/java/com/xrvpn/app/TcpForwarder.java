package com.xrvpn.app;

import android.net.VpnService;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP-форвардер через Cloudflare Worker (WebSocket + TLS).
 * Трафик зашифрован между телефоном и Worker.
 */
public class TcpForwarder {

    private static final AtomicLong ISN_CTR = new AtomicLong(
            (System.currentTimeMillis() / 1000L) << 10 & 0xFFFFFFFFL);

    private final VpnService      svc;
    private final PacketForwarder fwd;
    private final Map<String, TcpSession> table = new ConcurrentHashMap<>();

    TcpForwarder(VpnService svc, PacketForwarder fwd) {
        this.svc = svc; this.fwd = fwd;
    }

    void handle(byte[] pkt, int ihl, byte[] srcIP, byte[] dstIP) {
        int  srcPort  = IpUtil.tcpSrcPort(pkt, ihl);
        int  dstPort  = IpUtil.tcpDstPort(pkt, ihl);
        int  flags    = IpUtil.tcpFlags(pkt, ihl);
        long theirSeq = IpUtil.tcpSeq(pkt, ihl);
        String key    = key(srcIP, srcPort, dstIP, dstPort);

        // RST
        if ((flags & IpUtil.RST) != 0) {
            TcpSession s = table.remove(key);
            if (s != null) s.close();
            return;
        }

        // SYN — новое соединение
        if ((flags & IpUtil.SYN) != 0 && (flags & IpUtil.ACK) == 0) {
            TcpSession old = table.remove(key);
            if (old != null) old.close();

            long myISN = ISN_CTR.getAndAdd(128) & 0xFFFFFFFFL;
            TcpSession s = new TcpSession(srcIP, srcPort, dstIP, dstPort,
                    (theirSeq + 1) & 0xFFFFFFFFL,
                    (myISN   + 1) & 0xFFFFFFFFL);
            table.put(key, s);

            toTun(s, IpUtil.SYN | IpUtil.ACK, myISN, s.peerNextSeq, null);
            new Thread(() -> relay(s, key), "tcp-relay").start();
            return;
        }

        TcpSession s = table.get(key);
        if (s == null) {
            fwd.writeToTun(IpUtil.tcpPacket(dstIP, dstPort, srcIP, srcPort,
                    0L, (theirSeq + 1) & 0xFFFFFFFFL, IpUtil.RST | IpUtil.ACK, null));
            return;
        }

        // FIN
        if ((flags & IpUtil.FIN) != 0) {
            s.peerNextSeq = (theirSeq + 1) & 0xFFFFFFFFL;
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
            s.halfClose();
            return;
        }

        // Данные
        int dataOff = IpUtil.tcpDataOff(pkt, ihl);
        int dataLen = pkt.length - dataOff;
        if (dataLen > 0) {
            s.peerNextSeq = (theirSeq + dataLen) & 0xFFFFFFFFL;
            s.enqueue(pkt, dataOff, dataLen);
            toTun(s, IpUtil.ACK, s.myNextSeq, s.peerNextSeq, null);
        }
    }

    private void relay(TcpSession s, String key) {
        WsClient ws = new WsClient();
        try {
            // Целевой IP в строку для передачи Worker-у
            byte[] d = s.dstIP;
            String host = (d[0]&0xFF)+"."+(d[1]&0xFF)+"."+(d[2]&0xFF)+"."+(d[3]&0xFF);

            // Подключаемся через Worker (TLS-зашифрованный WebSocket)
            ws.connect(svc, host, s.dstPort);
            s.ws = ws;

            // TUN → Worker (отдельный поток)
            final WsClient finalWs = ws;
            new Thread(() -> {
                try {
                    while (true) {
                        byte[] chunk = s.dequeue();
                        if (chunk == null || (chunk.length == 0 && s.halfClosed)) break;
                        if (chunk.length > 0) finalWs.send(chunk);
                    }
                } catch (Exception e) { s.close(); }
            }, "tcp-tx").start();

            // Worker → TUN (этот поток)
            byte[] data;
            while ((data = ws.recv()) != null) {
                toTun(s, IpUtil.PSH | IpUtil.ACK, s.myNextSeq, s.peerNextSeq, data);
                s.myNextSeq = (s.myNextSeq + data.length) & 0xFFFFFFFFL;
            }

            // Сервер закрыл соединение
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
            ws.close();
        }
    }

    private void toTun(TcpSession s, int flags, long seq, long ack, byte[] data) {
        fwd.writeToTun(IpUtil.tcpPacket(
                s.dstIP, s.dstPort,
                s.srcIP, s.srcPort,
                seq, ack, flags, data));
    }

    void closeAll() { table.values().forEach(TcpSession::close); table.clear(); }

    private static String key(byte[] si, int sp, byte[] di, int dp) {
        return UdpForwarder.ip(si)+":"+sp+">"+UdpForwarder.ip(di)+":"+dp;
    }

    static class TcpSession {
        final byte[] srcIP, dstIP;
        final int    srcPort, dstPort;

        volatile long    peerNextSeq;
        volatile long    myNextSeq;
        volatile WsClient ws;
        volatile boolean  closed, halfClosed;

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
            queue.offer(new byte[0]);
        }

        void close() {
            closed = true; halfClosed = true;
            queue.offer(new byte[0]);
            try { if (ws != null) ws.close(); } catch (Exception ignored) {}
        }
    }
}
