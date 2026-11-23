'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createRelay, T_WELCOME, T_JOIN, T_LEAVE, T_DATA, T_SEND } = require('../relay');

const ROOM = 'a'.repeat(32);
const ROOM2 = 'b'.repeat(32);

async function start(opts) {
  const relay = createRelay({ pingIntervalMs: 60000, ...opts });
  await new Promise((r) => relay.server.listen(0, '127.0.0.1', r));
  const port = relay.server.address().port;
  return { relay, url: (room) => `ws://127.0.0.1:${port}/ws?room=${room}`, port };
}

/** A client that records every message and lets a test wait for the next matching one. */
function client(url) {
  const ws = new WebSocket(url);
  const got = [];
  const waiters = [];
  const c = { ws, got, id: null, closed: null };
  ws.on('message', (d) => {
    const m = Buffer.from(d);
    if (m[0] === T_WELCOME) c.id = m.readUInt32BE(1);
    got.push(m);
    for (const w of waiters.slice()) if (w.test(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m); }
  });
  ws.on('close', (code) => { c.closed = code; for (const w of waiters) w.resolve(null); });
  ws.on('error', () => {});
  c.open = new Promise((res, rej) => { ws.on('open', res); ws.on('unexpected-response', (_q, r) => rej(Object.assign(new Error('rejected'), { status: r.statusCode }))); ws.on('error', rej); });
  c.next = (test) => {
    const seen = got.find(test);
    if (seen) return Promise.resolve(seen);
    return new Promise((resolve) => waiters.push({ test, resolve }));
  };
  c.send = (dst, payload) => {
    const head = Buffer.alloc(5);
    head[0] = T_SEND;
    head.writeUInt32BE(dst, 1);
    ws.send(Buffer.concat([head, Buffer.from(payload)]));
  };
  c.close = () => new Promise((r) => { if (ws.readyState === 3) return r(); ws.on('close', r); ws.close(); });
  return c;
}

const isType = (t, id) => (m) => m[0] === t && (id === undefined || m.readUInt32BE(1) === id);
const until = async (cond, ms = 2000) => { const t = Date.now(); while (!cond()) { if (Date.now() - t > ms) throw new Error('timeout'); await new Promise((r) => setTimeout(r, 10)); } };

test('a member gets a WELCOME with its id and the payload limit', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM));
  await a.open;
  const w = await a.next(isType(T_WELCOME));
  assert.equal(w.length, 7);
  assert.notEqual(w.readUInt32BE(1), 0);
  assert.equal(w.readUInt16BE(5), relay.config.maxPayload);
  await a.close(); await relay.close();
});

test('members are told about each other, both ways', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM)); await b.open; await b.next(isType(T_WELCOME));
  await a.next(isType(T_JOIN, b.id));
  await b.next(isType(T_JOIN, a.id));
  await a.close(); await b.close(); await relay.close();
});

test('data goes only to the named member, with the sender id', async () => {
  const { relay, url } = await start();
  const [a, b, c] = [client(url(ROOM)), client(url(ROOM)), client(url(ROOM))];
  for (const x of [a, b, c]) { await x.open; await x.next(isType(T_WELCOME)); }
  await a.next(isType(T_JOIN, c.id));
  a.send(b.id, 'hello');
  const m = await b.next(isType(T_DATA));
  assert.equal(m.readUInt32BE(1), a.id);
  assert.equal(m.subarray(5).toString(), 'hello');
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(c.got.filter(isType(T_DATA)).length, 0, 'third member must not see it');
  assert.equal(a.got.filter(isType(T_DATA)).length, 0, 'sender must not get its own packet');
  for (const x of [a, b, c]) await x.close();
  await relay.close();
});

test('rooms are isolated from each other', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM2)); await b.open; await b.next(isType(T_WELCOME));
  a.send(b.id, 'x');
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(b.got.filter(isType(T_DATA)).length, 0);
  assert.equal(b.got.filter(isType(T_JOIN)).length, 0);
  await a.close(); await b.close(); await relay.close();
});

test('LEAVE is sent when a member goes, and an empty room is deleted', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM)); await b.open; await b.next(isType(T_WELCOME));
  await a.next(isType(T_JOIN, b.id));
  await b.close();
  await a.next(isType(T_LEAVE, b.id));
  assert.equal(relay.roomCount(), 1);
  await a.close();
  await until(() => relay.roomCount() === 0);
  assert.equal(relay.connectionCount(), 0);
  await relay.close();
});

test('packets to unknown or own ids are dropped silently', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  a.send(12345, 'x');
  a.send(a.id, 'x');
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(a.got.filter(isType(T_DATA)).length, 0);
  assert.equal(a.ws.readyState, WebSocket.OPEN);
  await a.close(); await relay.close();
});

test('bad room ids and wrong paths are refused', async () => {
  const { relay, url, port } = await start();
  for (const bad of ['', 'short', 'g'.repeat(32), 'A'.repeat(32), 'a'.repeat(33)]) {
    const c = client(url(bad));
    await assert.rejects(c.open, (e) => e.status === 400, `room "${bad}"`);
  }
  const c = client(`ws://127.0.0.1:${port}/other?room=${ROOM}`);
  await assert.rejects(c.open, (e) => e.status === 404);
  await relay.close();
});

test('a full room refuses the next member', async () => {
  const { relay, url } = await start({ maxRoomSize: 2 });
  const a = client(url(ROOM)); await a.open;
  const b = client(url(ROOM)); await b.open;
  const c = client(url(ROOM));
  await assert.rejects(c.open, (e) => e.status === 403);
  await a.close(); await b.close(); await relay.close();
});

test('an oversize packet closes that connection only', async () => {
  const { relay, url } = await start({ maxPayload: 64 });
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM)); await b.open; await b.next(isType(T_WELCOME));
  a.send(b.id, Buffer.alloc(200));
  await until(() => a.closed !== null);
  assert.equal(b.ws.readyState, WebSocket.OPEN);
  await b.close(); await relay.close();
});

test('text frames are refused', async () => {
  const { relay, url } = await start();
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  a.ws.send('hello');
  await until(() => a.closed !== null);
  assert.equal(a.closed, 1003);
  await relay.close();
});

test('a flood is cut off by the rate limit', async () => {
  const { relay, url } = await start({ burst: 20, packetsPerSecond: 1 });
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM)); await b.open; await b.next(isType(T_WELCOME));
  for (let i = 0; i < 100; i++) a.send(b.id, 'x');
  await until(() => a.closed !== null);
  assert.equal(a.closed, 1008);
  assert.ok(b.got.filter(isType(T_DATA)).length <= 21);
  await b.close(); await relay.close();
});

test('opening many new rooms from one address is limited (anti code-guessing)', async () => {
  const { relay, url } = await start({ newRoomsPerIpPerMinute: 3 });
  const open = [];
  for (let i = 0; i < 3; i++) { const c = client(url(String(i).repeat(32))); await c.open; open.push(c); }
  const c = client(url('9'.repeat(32)));
  await assert.rejects(c.open, (e) => e.status === 429);
  // Joining a room that already exists is not "new" and is still allowed.
  const joiner = client(url('0'.repeat(32)));
  await joiner.open;
  for (const x of [...open, joiner]) await x.close();
  await relay.close();
});

test('connections per address are limited', async () => {
  const { relay, url } = await start({ maxConnectionsPerIp: 2 });
  const a = client(url(ROOM)); await a.open;
  const b = client(url(ROOM)); await b.open;
  const c = client(url(ROOM));
  await assert.rejects(c.open, (e) => e.status === 429);
  await a.close(); await b.close(); await relay.close();
});

test('X-Forwarded-For is used only when trustProxy is on', async () => {
  const off = await start({ maxConnectionsPerIp: 1, trustProxy: false });
  const mk = (u, ip) => { const ws = new WebSocket(u, { headers: { 'x-forwarded-for': ip } }); ws.on('error', () => {}); return ws; };
  const a1 = mk(off.url(ROOM), '1.1.1.1');
  await new Promise((r) => a1.on('open', r));
  const a2 = mk(off.url(ROOM), '2.2.2.2');
  const status = await new Promise((r) => a2.on('unexpected-response', (_q, res) => r(res.statusCode)));
  assert.equal(status, 429, 'spoofed header must not dodge the limit');
  a1.close(); await off.relay.close();

  const on = await start({ maxConnectionsPerIp: 1, trustProxy: true });
  const b1 = mk(on.url(ROOM), '1.1.1.1');
  await new Promise((r) => b1.on('open', r));
  const b2 = mk(on.url(ROOM), '2.2.2.2');
  await new Promise((r) => b2.on('open', r));
  b1.close(); b2.close(); await on.relay.close();
});

test('healthz answers and other URLs do not leak anything', async () => {
  const { relay, port } = await start();
  const ok = await fetch(`http://127.0.0.1:${port}/healthz`);
  assert.equal(ok.status, 200);
  assert.equal(await ok.text(), 'ok');
  const other = await fetch(`http://127.0.0.1:${port}/rooms`);
  assert.equal(other.status, 404);
  await relay.close();
});

test('a member that stops answering pings is dropped', async () => {
  const { relay, url } = await start({ pingIntervalMs: 50 });
  const a = client(url(ROOM)); await a.open; await a.next(isType(T_WELCOME));
  const b = client(url(ROOM)); await b.open; await b.next(isType(T_WELCOME));
  await a.next(isType(T_JOIN, b.id));
  b.ws._socket.pause(); // a phone that went into a tunnel: reads nothing, so never pongs
  await a.next(isType(T_LEAVE, b.id));
  await a.close(); await relay.close();
});
