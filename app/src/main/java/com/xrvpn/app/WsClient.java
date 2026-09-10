package com.xrvpn.app;

import android.net.VpnService;
import android.util.Base64;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Минимальный WebSocket-клиент поверх TLS.
 * Подключается к Cloudflare Worker и туннелирует TCP-соединение.
 */
public class WsClient {

    // Только hostname, без https:// и без /
    public static final String WORKER_HOST = "xr-vpn.xeetrav329.workers.dev";
    public static final int    WORKER_PORT = 443;

    private SSLSocket    ssl;
    private InputStream  in;
    private OutputStream out;

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * @param svc        нужен для protect() — чтобы сокет не зациклился в VPN
     * @param targetHost куда Worker должен подключиться (напр. "google.com")
     * @param targetPort порт назначения (80, 443, ...)
     */
    public void connect(VpnService svc, String targetHost, int targetPort) throws Exception {
        Socket raw = new Socket(InetAddress.getByName(WORKER_HOST), WORKER_PORT);
        if (svc != null) {
            svc.protect(raw);
        }

        // getDefault() объявлен как SocketFactory — нужен явный cast к SSLSocketFactory
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        ssl = (SSLSocket) factory.createSocket(raw, WORKER_HOST, WORKER_PORT, true);
        ssl.startHandshake();

        in  = new BufferedInputStream(ssl.getInputStream());
        out = ssl.getOutputStream();

        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        String path = "/?host=" + targetHost + "&port=" + targetPort;
        String req  = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + WORKER_HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes("UTF-8"));
        out.flush();

        StringBuilder sb = new StringBuilder();
        int prev = 0, b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (prev == '\r' && b == '\n'
                    && sb.length() >= 4
                    && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break;
            prev = b;
        }
        if (!sb.toString().contains("101")) {
            throw new IOException("WS handshake failed: " + sb);
        }
    }

    public synchronized void send(byte[] data) throws IOException {
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);

        byte[] masked = new byte[data.length];
        for (int i = 0; i < data.length; i++)
            masked[i] = (byte)(data[i] ^ mask[i % 4]);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x82); // FIN=1, opcode=2 (binary)

        int len = data.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0xFE);
            frame.write(len >> 8);
            frame.write(len & 0xFF);
        } else {
            frame.write(0xFF);
            for (int i = 7; i >= 0; i--) frame.write((len >> (i * 8)) & 0xFF);
        }
        frame.write(mask);
        frame.write(masked);

        out.write(frame.toByteArray());
        out.flush();
    }

    public byte[] recv() throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 == -1 || b1 == -1) return null;

        int opcode = b0 & 0x0F;
        if (opcode == 0x8) return null;

        boolean masked = (b1 & 0x80) != 0;
        int len = b1 & 0x7F;

        if (len == 126) {
            len = (in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }

        byte[] mask = new byte[4];
        if (masked) readFully(mask);

        byte[] payload = new byte[len];
        readFully(payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++)
                payload[i] ^= mask[i % 4];
        }

        return payload;
    }

    public void close() {
        try {
            out.write(new byte[]{(byte)0x88, (byte)0x80, 0, 0, 0, 0});
            out.flush();
        } catch (Exception ignored) {}
        try { if (ssl != null) ssl.close(); } catch (Exception ignored) {}
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new EOFException();
            off += n;
        }
    }
}
