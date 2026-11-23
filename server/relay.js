'use strict';
/**
 * Rider Intercom relay.
 *
 * A room is a set of WebSocket connections that asked for the same 32-hex room id. The
 * server tells each member who else is in the room and forwards packets between named
 * members. That is all it does. Riders encrypt and authenticate every packet end to end with
 * their ride code (the relay has no key), so the relay sees only: IP addresses, room ids
 * (a slow hash of the ride code), packet sizes and timing. It keeps nothing on disk and logs
 * no packet contents.
 *
 * Wire format (binary WebSocket messages, big-endian):
 *   server -> client
 *     0x01 WELCOME  id:u32 maxPayload:u16     your id in this room
 *     0x02 JOIN     id:u32                    someone is in the room (also sent for each existing member on connect)
 *     0x03 LEAVE    id:u32                    someone left
 *     0x04 DATA     src:u32 payload...        a packet from `src`
 *   client -> server
 *     0x10 SEND     dst:u32 payload...        forward `payload` to member `dst`
 */
const http = require('node:http');
const crypto = require('node:crypto');
const { WebSocketServer } = require('ws');

const T_WELCOME = 0x01;
const T_JOIN = 0x02;
const T_LEAVE = 0x03;
const T_DATA = 0x04;
const T_SEND = 0x10;

const ROOM_RE = /^[0-9a-f]{32}$/;

const DEFAULTS = {
  maxRoomSize: 12,
  maxPayload: 2048,
  maxRooms: 2000,
  maxConnections: 4000,
  maxConnectionsPerIp: 24,
  // Opening a *new* room is what a code-guessing attacker has to do for every guess.
  newRoomsPerIpPerMinute: 12,
  // Packets per second per connection: 50 audio frames to each of up to 11 peers, with room to spare.
  packetsPerSecond: 800,
  burst: 1600,
  pingIntervalMs: 20000,
  // Behind Fly/Render/nginx the client IP is in X-Forwarded-For. Only turn this on there.
  trustProxy: false,
  now: () => Date.now(),
};

function createRelay(options = {}) {
  const cfg = { ...DEFAULTS, ...options };
  /** @type {Map<string, Map<number, any>>} */
  const rooms = new Map();
  const perIp = new Map();
  const newRoomLog = new Map();
  let connections = 0;

  const stats = { connections: 0, packets: 0, rejected: 0 };

  const server = http.createServer((req, res) => {
    if (req.url === '/healthz') {
      res.writeHead(200, { 'content-type': 'text/plain' });
      res.end('ok');
      return;
    }
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('Rider Intercom relay');
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: cfg.maxPayload + 8 });

  function clientIp(req) {
    if (cfg.trustProxy) {
      const xff = req.headers['x-forwarded-for'];
      if (typeof xff === 'string' && xff.length > 0) return xff.split(',')[0].trim();
    }
    return req.socket.remoteAddress || 'unknown';
  }

  function allowNewRoom(ip) {
    const t = cfg.now();
    const list = (newRoomLog.get(ip) || []).filter((x) => t - x < 60000);
    if (list.length >= cfg.newRoomsPerIpPerMinute) {
      newRoomLog.set(ip, list);
      return false;
    }
    list.push(t);
    newRoomLog.set(ip, list);
    return true;
  }

  function reject(socket, code, text) {
    stats.rejected++;
    socket.write(`HTTP/1.1 ${code} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    socket.destroy();
  }

  server.on('upgrade', (req, socket, head) => {
    let url;
    try {
      url = new URL(req.url, 'http://x');
    } catch (e) {
      return reject(socket, 400, 'Bad Request');
    }
    if (url.pathname !== '/ws') return reject(socket, 404, 'Not Found');
    const room = url.searchParams.get('room') || '';
    if (!ROOM_RE.test(room)) return reject(socket, 400, 'Bad Request');

    const ip = clientIp(req);
    if (connections >= cfg.maxConnections) return reject(socket, 503, 'Service Unavailable');
    if ((perIp.get(ip) || 0) >= cfg.maxConnectionsPerIp) return reject(socket, 429, 'Too Many Requests');

    const existing = rooms.get(room);
    if (!existing) {
      if (rooms.size >= cfg.maxRooms) return reject(socket, 503, 'Service Unavailable');
      if (!allowNewRoom(ip)) return reject(socket, 429, 'Too Many Requests');
    } else if (existing.size >= cfg.maxRoomSize) {
      return reject(socket, 403, 'Forbidden');
    }

    wss.handleUpgrade(req, socket, head, (ws) => join(ws, room, ip));
  });

  function newId(members) {
    for (;;) {
      const id = crypto.randomBytes(4).readUInt32BE(0);
      if (id !== 0 && !members.has(id)) return id;
    }
  }

  function frame(type, id, payload) {
    const head = Buffer.alloc(5);
    head[0] = type;
    head.writeUInt32BE(id, 1);
    return payload ? Buffer.concat([head, payload]) : head;
  }

  function join(ws, room, ip) {
    let members = rooms.get(room);
    if (!members) {
      members = new Map();
      rooms.set(room, members);
    }
    const id = newId(members);
    const conn = {
      id,
      ws,
      ip,
      alive: true,
      tokens: cfg.burst,
      refilledAt: cfg.now(),
    };
    connections++;
    stats.connections++;
    perIp.set(ip, (perIp.get(ip) || 0) + 1);

    const welcome = Buffer.alloc(7);
    welcome[0] = T_WELCOME;
    welcome.writeUInt32BE(id, 1);
    welcome.writeUInt16BE(cfg.maxPayload, 5);
    ws.send(welcome);
    for (const other of members.values()) {
      ws.send(frame(T_JOIN, other.id));
      other.ws.send(frame(T_JOIN, id));
    }
    members.set(id, conn);

    ws.on('pong', () => { conn.alive = true; });
    ws.on('message', (data, isBinary) => {
      if (!isBinary || !Buffer.isBuffer(data)) return ws.close(1003, 'binary only');
      onMessage(conn, members, data);
    });
    ws.on('error', () => { /* the close handler cleans up */ });
    ws.on('close', () => leave(conn, room, members));
  }

  function takeToken(conn) {
    const t = cfg.now();
    const add = ((t - conn.refilledAt) / 1000) * cfg.packetsPerSecond;
    conn.tokens = Math.min(cfg.burst, conn.tokens + add);
    conn.refilledAt = t;
    if (conn.tokens < 1) return false;
    conn.tokens -= 1;
    return true;
  }

  function onMessage(conn, members, data) {
    if (data.length < 5 || data[0] !== T_SEND) return; // unknown or short: ignore
    if (data.length - 5 > cfg.maxPayload) return conn.ws.close(1009, 'too big');
    if (!takeToken(conn)) {
      stats.rejected++;
      return conn.ws.close(1008, 'rate limit');
    }
    const dst = data.readUInt32BE(1);
    const target = members.get(dst);
    if (!target || target === conn) return;
    if (target.ws.bufferedAmount > 1 << 20) return; // a stuck receiver must not grow our memory
    stats.packets++;
    target.ws.send(frame(T_DATA, conn.id, data.subarray(5)));
  }

  function leave(conn, room, members) {
    if (!members.delete(conn.id)) return;
    connections--;
    const n = (perIp.get(conn.ip) || 1) - 1;
    if (n <= 0) perIp.delete(conn.ip);
    else perIp.set(conn.ip, n);
    if (members.size === 0) {
      rooms.delete(room);
      return;
    }
    for (const other of members.values()) {
      if (other.ws.readyState === 1) other.ws.send(frame(T_LEAVE, conn.id));
    }
  }

  const pinger = setInterval(() => {
    for (const members of rooms.values()) {
      for (const c of members.values()) {
        if (!c.alive) { c.ws.terminate(); continue; }
        c.alive = false;
        try { c.ws.ping(); } catch (e) { /* closing */ }
      }
    }
    const t = cfg.now();
    for (const [ip, list] of newRoomLog) {
      if (!list.some((x) => t - x < 60000)) newRoomLog.delete(ip);
    }
  }, cfg.pingIntervalMs);
  pinger.unref();

  function close() {
    clearInterval(pinger);
    for (const members of rooms.values()) for (const c of members.values()) c.ws.terminate();
    return new Promise((resolve) => {
      wss.close(() => server.close(() => resolve()));
      server.closeAllConnections?.();
    });
  }

  return {
    server,
    close,
    stats,
    roomCount: () => rooms.size,
    connectionCount: () => connections,
    config: cfg,
  };
}

module.exports = { createRelay, T_WELCOME, T_JOIN, T_LEAVE, T_DATA, T_SEND, ROOM_RE };
