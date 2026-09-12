package com.xrvpn.app;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Reads raw IPv4 packets from the TUN file descriptor and dispatches them
 * to the appropriate forwarder (UDP or TCP).  Responses are written back
 * via writeToTun(), which the forwarders call from their own threads.
 */
public class PacketForwarder implements Runnable {

    static final int MTU = 1500;

    private final VpnService          svc;
    private final ParcelFileDescriptor pfd;
    private final String               mode;
    private final JSONArray            bypassList;

    private volatile boolean running = true;

    private FileOutputStream tunOut;

    private final UdpForwarder udp;
    private final TcpForwarder tcp;

    PacketForwarder(VpnService svc, ParcelFileDescriptor pfd,
                    String mode, JSONArray bypassList) {
        this.svc        = svc;
        this.pfd        = pfd;
        this.mode       = mode;
        this.bypassList = bypassList;
        this.udp = new UdpForwarder(svc, this);
        this.tcp = new TcpForwarder(svc, this);
    }

    @Override
    public void run() {
        tunOut = new FileOutputStream(pfd.getFileDescriptor());
        FileInputStream tunIn = new FileInputStream(pfd.getFileDescriptor());
        byte[] buf = new byte[MTU];

        while (running) {
            try {
                int len = tunIn.read(buf);
                if (len < 20) continue;

                // Only IPv4
                if ((buf[0] >> 4 & 0xF) != 4) continue;

                int    ihl   = IpUtil.ihl(buf);
                int    proto = IpUtil.proto(buf);
                byte[] srcIP = IpUtil.srcIP(buf);
                byte[] dstIP = IpUtil.dstIP(buf);
                byte[] pkt   = Arrays.copyOf(buf, len);

                if (proto == 17)     udp.handle(pkt, ihl, srcIP, dstIP);
                else if (proto == 6) tcp.handle(pkt, ihl, srcIP, dstIP);
                // ICMP etc. — dropped (could add ICMP echo later)

            } catch (Exception e) {
                if (running) e.printStackTrace();
            }
        }

        udp.closeAll();
        tcp.closeAll();
    }

    /** Thread-safe write of a raw IP packet back to the TUN (to the app). */
    synchronized void writeToTun(byte[] packet) {
        if (!running || tunOut == null) return;
        try { tunOut.write(packet); }
        catch (Exception ignored) {}
    }

    void stop() { running = false; }
    boolean isStopped() { return !running; }
}
