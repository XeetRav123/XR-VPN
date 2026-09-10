package com.xrvpn.app;

import android.net.VpnService;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * WebSocket client over TLS → Cloudflare Worker TCP proxy.
 * Protocol: GET /?host=&port= → 101 Switching Protocols → binary frames = raw TCP bytes.
 */
public class WsClient {

    private static final String TAG = "WsClient";

    /** Mutable so Service can override from config. */
    public static volatile String WORKER_HOST = "xr-vpn.xeetrav329.workers.dev";
    public static final int WORKER_PORT = 443;

    private SSLSocket ssl;
    private InputStream in;
    private OutputStream out;

    private static final SecureRandom RNG = new SecureRandom();

    public void connect(VpnService svc, String targetHost, int targetPort) throws Exception {
        String host = WORKER_HOST;
        Log.i(TAG, "WS connect worker=" + host + " target=" + targetHost + ":" + targetPort);

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(InetAddress.getByName(host), WORKER_PORT), 12_000);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(0); // streaming
        if (svc != null) {
            boolean ok = svc.protect(raw);
            Log.i(TAG, "protect(socket)=" + ok);
        }

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        ssl = (SSLSocket) factory.createSocket(raw, host, WORKER_PORT, true);
        ssl.setTcpNoDelay(true);
        ssl.startHandshake();

        in  = new BufferedInputStream(ssl.getInputStream());
        out = ssl.getOutputStream();

        byte[] keyBytes = new byte[16];
        RNG.nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        String path = "/?host=" + URLEncoder.encode(targetHost, "UTF-8")
                + "&port=" + targetPort;

        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: XR-VPN/1.0\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read HTTP response headers
        StringBuilder sb = new StringBuilder();
        int prev = 0, b;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            // temporary read timeout for handshake
            ssl.setSoTimeout(15_000);
            b = in.read();
            if (b == -1) break;
            sb.append((char) b);
            if (prev == '\r' && b == '\n'
                    && sb.length() >= 4
                    && sb.substring(sb.length() - 4).equals("\r\n\r\n")) break;
            prev = b;
        }
        ssl.setSoTimeout(0);

        String resp = sb.toString();
        if (!resp.contains("101")) {
            Log.e(TAG, "handshake fail: " + resp);
            throw new IOException("WS handshake failed: " + resp);
        }
        Log.i(TAG, "WS 101 OK → " + targetHost + ":" + targetPort);
    }

    public synchronized void send(byte[] data) throws IOException {
        if (out == null) throw new IOException("not connected");
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);

        byte[] masked = new byte[data.length];
        for (int i = 0; i < data.length; i++)
            masked[i] = (byte) (data[i] ^ mask[i % 4]);

        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 14);
        frame.write(0x82); // FIN + binary

        int len = data.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0xFE);
            frame.write((len >> 8) & 0xFF);
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
        if (in == null) return null;

        int b0 = in.read();
        int b1 = in.read();
        if (b0 == -1 || b1 == -1) return null;

        int opcode = b0 & 0x0F;
        if (opcode == 0x8) return null; // close
        if (opcode == 0x9) { // ping → pong
            // ignore payload of ping for simplicity; still consume length
            consumeFramePayload(b1);
            return recv();
        }
        if (opcode == 0xA) { // pong
            consumeFramePayload(b1);
            return recv();
        }

        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        if (len > 2_000_000) throw new IOException("frame too large: " + len);

        byte[] mask = new byte[4];
        if (masked) readFully(mask);

        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++)
                payload[i] ^= mask[i % 4];
        }
        return payload;
    }

    private void consumeFramePayload(int b1) throws IOException {
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        if (masked) {
            byte[] mask = new byte[4];
            readFully(mask);
        }
        byte[] skip = new byte[(int) Math.min(len, 65536)];
        long left = len;
        while (left > 0) {
            int n = in.read(skip, 0, (int) Math.min(left, skip.length));
            if (n < 0) break;
            left -= n;
        }
    }

    public void close() {
        try {
            if (out != null) {
                // masked close frame (client must mask)
                byte[] mask = new byte[4];
                RNG.nextBytes(mask);
                out.write(new byte[]{
                        (byte) 0x88, (byte) 0x80,
                        mask[0], mask[1], mask[2], mask[3]
                });
                out.flush();
            }
        } catch (Exception ignored) {}
        try { if (ssl != null) ssl.close(); } catch (Exception ignored) {}
        ssl = null; in = null; out = null;
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
