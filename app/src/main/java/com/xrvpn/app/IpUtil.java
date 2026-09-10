package com.xrvpn.app;

public final class IpUtil {

    private IpUtil() {}

    // ── IPv4 ─────────────────────────────────────────────────────────────
    public static int    ihl(byte[] p)       { return (p[0] & 0xF) * 4; }
    public static int    proto(byte[] p)     { return p[9] & 0xFF; }
    public static byte[] srcIP(byte[] p)     { return new byte[]{p[12],p[13],p[14],p[15]}; }
    public static byte[] dstIP(byte[] p)     { return new byte[]{p[16],p[17],p[18],p[19]}; }

    // ── UDP ───────────────────────────────────────────────────────────────
    public static int udpSrcPort(byte[] p, int ihl) { return u16(p, ihl);     }
    public static int udpDstPort(byte[] p, int ihl) { return u16(p, ihl + 2); }
    public static int udpDataOff(int ihl)            { return ihl + 8;         }

    // ── TCP ───────────────────────────────────────────────────────────────
    public static int  tcpSrcPort(byte[] p, int ihl)  { return u16(p, ihl);      }
    public static int  tcpDstPort(byte[] p, int ihl)  { return u16(p, ihl + 2);  }
    public static long tcpSeq    (byte[] p, int ihl)  { return u32(p, ihl + 4);  }
    public static long tcpAck    (byte[] p, int ihl)  { return u32(p, ihl + 8);  }
    public static int  tcpDataOff(byte[] p, int ihl)  { return ihl + ((p[ihl + 12] >> 4 & 0xF) * 4); }
    public static int  tcpFlags  (byte[] p, int ihl)  { return p[ihl + 13] & 0xFF; }

    public static final int SYN = 0x02, ACK = 0x10, FIN = 0x01,
                             RST = 0x04, PSH = 0x08;

    // ── Build IPv4 + UDP packet ───────────────────────────────────────────
    public static byte[] udpPacket(byte[] srcIP, int srcPort,
                                   byte[] dstIP, int dstPort,
                                   byte[] data) {
        int total = 28 + data.length;
        byte[] p  = new byte[total];
        ip4Header(p, 17, srcIP, dstIP, total);

        p[20] = (byte)(srcPort >> 8); p[21] = (byte) srcPort;
        p[22] = (byte)(dstPort >> 8); p[23] = (byte) dstPort;
        int ul = 8 + data.length;
        p[24] = (byte)(ul >> 8); p[25] = (byte) ul;
        // UDP checksum = 0 (optional, valid per RFC 768)
        System.arraycopy(data, 0, p, 28, data.length);
        return p;
    }

    // ── Build IPv4 + TCP packet ───────────────────────────────────────────
    public static byte[] tcpPacket(byte[] srcIP, int srcPort,
                                   byte[] dstIP, int dstPort,
                                   long seq, long ack, int flags,
                                   byte[] data) {
        int dlen  = data != null ? data.length : 0;
        int total = 40 + dlen;
        byte[] p  = new byte[total];
        ip4Header(p, 6, srcIP, dstIP, total);

        p[20] = (byte)(srcPort >> 8); p[21] = (byte) srcPort;
        p[22] = (byte)(dstPort >> 8); p[23] = (byte) dstPort;
        putU32(p, 24, seq);
        putU32(p, 28, ack);
        p[32] = 0x50;                       // data offset = 5 × 4 = 20 bytes
        p[33] = (byte) flags;
        p[34] = (byte)(65535 >> 8); p[35] = (byte) 65535; // window
        // checksum at [36-37] filled below
        if (data != null) System.arraycopy(data, 0, p, 40, dlen);

        int ck = tcpChecksum(srcIP, dstIP, p, 20, 20 + dlen);
        p[36] = (byte)(ck >> 8); p[37] = (byte) ck;
        return p;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private static void ip4Header(byte[] p, int proto, byte[] src, byte[] dst, int total) {
        p[0] = 0x45;                           // version=4, IHL=5
        p[2] = (byte)(total >> 8); p[3] = (byte) total;
        p[6] = 0x40;                           // DF flag
        p[8] = 64;                             // TTL
        p[9] = (byte) proto;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        int ck = checksum(p, 0, 20);
        p[10] = (byte)(ck >> 8); p[11] = (byte) ck;
    }

    public static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len - 1; i += 2)
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        if (len % 2 != 0) sum += (b[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private static int tcpChecksum(byte[] src, byte[] dst, byte[] pkt, int off, int tcpLen) {
        byte[] ph = new byte[12 + tcpLen];
        System.arraycopy(src, 0, ph, 0, 4);
        System.arraycopy(dst, 0, ph, 4, 4);
        ph[9]  = 6;
        ph[10] = (byte)(tcpLen >> 8); ph[11] = (byte) tcpLen;
        System.arraycopy(pkt, off, ph, 12, tcpLen);
        return checksum(ph, 0, ph.length);
    }

    private static int  u16(byte[] p, int o) {
        return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF);
    }
    private static long u32(byte[] p, int o) {
        return ((p[o]&0xFFL)<<24)|((p[o+1]&0xFFL)<<16)|((p[o+2]&0xFFL)<<8)|(p[o+3]&0xFFL);
    }
    private static void putU32(byte[] p, int o, long v) {
        p[o]=(byte)(v>>24); p[o+1]=(byte)(v>>16); p[o+2]=(byte)(v>>8); p[o+3]=(byte)v;
    }
}
