// XR VPN — WebSocket TCP proxy
// Деплоится на Render.com как Node.js Web Service

const http = require('http');
const net  = require('net');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end('XR VPN Proxy — OK');
});

server.on('upgrade', (req, socket, head) => {
    const url    = new URL(req.url, `http://localhost`);
    const host   = url.searchParams.get('host');
    const port   = parseInt(url.searchParams.get('port') || '80');

    if (!host) {
        socket.destroy();
        return;
    }

    // WebSocket handshake
    const key    = req.headers['sec-websocket-key'];
    const accept = require('crypto')
        .createHash('sha1')
        .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
        .digest('base64');

    socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
    );

    // Подключаемся к реальному серверу
    const tcp = net.createConnection({ host, port }, () => {
        console.log(`Tunnel: ${host}:${port}`);
    });

    // WebSocket frame parser
    let buf = Buffer.alloc(0);

    socket.on('data', chunk => {
        buf = Buffer.concat([buf, chunk]);
        while (buf.length >= 2) {
            const masked  = (buf[1] & 0x80) !== 0;
            let   len     = buf[1] & 0x7F;
            let   offset  = 2;

            if (len === 126) {
                if (buf.length < 4) break;
                len    = buf.readUInt16BE(2);
                offset = 4;
            } else if (len === 127) {
                if (buf.length < 10) break;
                len    = Number(buf.readBigUInt64BE(2));
                offset = 10;
            }

            const maskLen  = masked ? 4 : 0;
            const totalLen = offset + maskLen + len;
            if (buf.length < totalLen) break;

            const opcode = buf[0] & 0x0F;
            if (opcode === 0x8) { // close
                tcp.destroy();
                socket.destroy();
                break;
            }

            if (opcode === 0x2 || opcode === 0x1) { // binary or text
                let payload = buf.slice(offset + maskLen, totalLen);
                if (masked) {
                    const mask = buf.slice(offset, offset + 4);
                    payload    = Buffer.from(payload);
                    for (let i = 0; i < payload.length; i++)
                        payload[i] ^= mask[i % 4];
                }
                tcp.write(payload);
            }

            buf = buf.slice(totalLen);
        }
    });

    // Реальный сервер → WebSocket (без маски — сервер не маскирует)
    tcp.on('data', data => {
        const header = buildWsHeader(data.length);
        socket.write(Buffer.concat([header, data]));
    });

    tcp.on('close', () => socket.destroy());
    tcp.on('error', () => socket.destroy());
    socket.on('close', () => tcp.destroy());
    socket.on('error', () => tcp.destroy());
});

function buildWsHeader(len) {
    if (len < 126) {
        return Buffer.from([0x82, len]);
    } else if (len < 65536) {
        const h = Buffer.alloc(4);
        h[0] = 0x82; h[1] = 126;
        h.writeUInt16BE(len, 2);
        return h;
    } else {
        const h = Buffer.alloc(10);
        h[0] = 0x82; h[1] = 127;
        h.writeBigUInt64BE(BigInt(len), 2);
        return h;
    }
}

server.listen(PORT, () => console.log(`XR VPN proxy listening on ${PORT}`));
