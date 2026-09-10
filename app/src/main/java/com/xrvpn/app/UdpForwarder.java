package com.xrvpn.app;

import android.net.VpnService;

import java.net.*;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles UDP packets read from the TUN interface.
 *
 * For each unique (srcIP:srcPort → dstIP:dstPort) flow a DatagramSocket is
 * opened, protected from the VPN (so it exits the real network interface),
 * and a reader thread writes responses back into the TUN.
 */
public class UdpForwarder {

    private static final int SESSION_TTL_MS = 60_000;

    private final VpnService      svc;
    private final PacketForwarder fwd;
    private final Map<String, UdpSession> table = new ConcurrentHashMap<>();

    UdpForwarder(VpnService svc, PacketForwarder fwd) {
        this.svc = svc; this.fwd = fwd;
    }

    void handle(byte[] pkt, int ihl, byte[] srcIP, byte[] dstIP) {
        int srcPort = IpUtil.udpSrcPort(pkt, ihl);
        int dstPort = IpUtil.udpDstPort(pkt, ihl);
        int dataOff = IpUtil.udpDataOff(ihl);
        int dataLen = pkt.length - dataOff;
        if (dataLen < 0) return;

        String key = key(srcIP, srcPort, dstIP, dstPort);

        UdpSession s = table.computeIfAbsent(key,
                k -> open(srcIP, srcPort, dstIP, dstPort));
        if (s == null) return;

        try {
            byte[] data = Arrays.copyOfRange(pkt, dataOff, dataOff + dataLen);
            s.socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByAddress(dstIP), dstPort));
        } catch (Exception e) {
            table.remove(key);
            close(s);
        }
    }

    private UdpSession open(byte[] srcIP, int srcPort, byte[] dstIP, int dstPort) {
        try {
            DatagramSocket sock = new DatagramSocket();
            sock.setSoTimeout(SESSION_TTL_MS);
            svc.protect(sock);
            UdpSession s = new UdpSession(sock, srcIP, srcPort, dstIP, dstPort);
            startReader(s);
            return s;
        } catch (Exception e) { return null; }
    }

    private void startReader(UdpSession s) {
        new Thread(() -> {
            byte[] buf = new byte[PacketForwarder.MTU];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            while (true) {
                try {
                    s.socket.receive(dp);
                    // Reverse src/dst so the packet looks like it came from the real server
                    byte[] reply = IpUtil.udpPacket(
                            s.dstIP,  dp.getPort(),   // from: real server
                            s.srcIP,  s.srcPort,       // to:   app
                            Arrays.copyOf(dp.getData(), dp.getLength()));
                    fwd.writeToTun(reply);
                } catch (SocketTimeoutException e) {
                    break; // session expired
                } catch (Exception e) {
                    break;
                }
            }
            close(s);
        }, "udp-rx").start();
    }

    private void close(UdpSession s) {
        try { s.socket.close(); } catch (Exception ignored) {}
    }

    void closeAll() {
        table.values().forEach(this::close);
        table.clear();
    }

    private static String key(byte[] si, int sp, byte[] di, int dp) {
        return ip(si) + ":" + sp + ">" + ip(di) + ":" + dp;
    }
    static String ip(byte[] a) {
        return (a[0]&0xFF)+"."+(a[1]&0xFF)+"."+(a[2]&0xFF)+"."+(a[3]&0xFF);
    }

    static class UdpSession {
        final DatagramSocket socket;
        final byte[] srcIP, dstIP;
        final int    srcPort;
        UdpSession(DatagramSocket sock, byte[] srcIP, int srcPort, byte[] dstIP, int dstPort) {
            this.socket  = sock;
            this.srcIP   = srcIP;  this.srcPort = srcPort;
            this.dstIP   = dstIP;
        }
    }
}
