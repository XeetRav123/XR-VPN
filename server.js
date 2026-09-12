const http = require('http');
const net  = require('net');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
    res.writeHead(200);
    res.end('XR VPN Proxy — OK');
});

const wss = new WebSocket.Server({ server });

wss.on('connection', (ws, req) => {
    const url  = new URL(req.url, 'http://localhost');
    const host = url.searchParams.get('host');
    const port = parseInt(url.searchParams.get('port') || '80');

    if (!host) { ws.close(1008, 'Missing host'); return; }

    console.log(`Tunnel: ${host}:${port}`);

    const tcp = net.createConnection({ host, port });

    ws.on('message', (data) => {
        if (tcp.writable) tcp.write(data);
    });

    tcp.on('data', (data) => {
        if (ws.readyState === WebSocket.OPEN) ws.send(data, { binary: true });
    });

    tcp.on('close', () => { try { ws.close(1000); } catch(_) {} });
    tcp.on('error', (e) => {
        console.error(`TCP error: ${e.message}`);
        try { ws.close(1011, e.message); } catch(_) {}
    });

    ws.on('close', () => tcp.destroy());
    ws.on('error', () => tcp.destroy());
});

// Keep-alive чтобы Render не засыпал
setInterval(() => {
    require('https').get(`https://${process.env.RENDER_EXTERNAL_HOSTNAME || 'localhost'}/`, () => {})
        .on('error', () => {});
}, 10 * 60 * 1000);

server.listen(PORT, () => console.log(`XR VPN proxy listening on ${PORT}`));
