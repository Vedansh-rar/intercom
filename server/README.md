# Rider Intercom relay

The relay lets riders talk over mobile data when they are out of radio range of each other
(city and plains riding, where there is phone signal). It is optional: Bluetooth, Wi-Fi Direct
and Nearby group rides do not use it.

**What it is.** A small Node server. Riders connect with a WebSocket and ask for a *room*; the
relay tells each member who else is in the room and forwards packets between them. That is all.

**What it cannot see.** Every packet is encrypted and authenticated between the two riders
(AES-GCM with a key from the ride code, the same handshake as every other ride). The relay holds
no key. It sees IP addresses, a room id, packet sizes and timing. It writes nothing to disk and
logs no addresses or contents.

**What it cannot protect against.**
- *Who is talking to whom, and when.* Packet sizes and timing are visible to whoever runs it.
- *Guessing the ride code.* The room id is a slow hash of the code, so the operator (or a network
  eavesdropper who sees the room id) can test guesses offline. Use a five-word code, never a
  6-digit number: the app refuses number codes for internet rides for this reason.
- *Availability.* If the server or the rider's mobile data is down, the ride drops. The app
  reconnects by itself.

## Run it

```bash
cd server
npm ci
npm start            # listens on :8080 (PORT to change)
npm test             # 16 tests
```

Riders must reach it over **`wss://`** (TLS); the app refuses plain `ws://`. Put it behind
something that terminates TLS and set `TRUST_PROXY=1` so rate limits see the rider's real address:

| Host | How |
|---|---|
| Fly.io | `fly launch` in `server/` (uses the Dockerfile), `fly secrets set TRUST_PROXY=1`. Fly gives you `wss://<app>.fly.dev`. |
| Render / Railway | New web service from this folder, Docker, set `TRUST_PROXY=1`. |
| Your own VPS | `docker build -t relay . && docker run -d -p 8080:8080 -e TRUST_PROXY=1 relay`, with Caddy or nginx in front for TLS. |

Then in the app: Settings > Internet ride > relay address `wss://your-host`, switch internet
rides on, and start a group ride with a five-word code on every phone.

## Limits (all in `relay.js`)

12 riders per room, 2 KB per packet, 24 connections and 12 *new* rooms per minute per address
(a code guesser has to open a new room per guess), 800 packets per second per rider, idle
connections dropped after missed pings. A small free instance is plenty for a few groups.

## Data use

Voice is Opus at 16 kbps, sent only while someone has the floor. With overheads, each listener
receives about 35 kbps (about 16 MB per hour of talking) and the speaker sends that much to
each other rider. A convoy of five where one person talks at a time uses far less than a video call.

## Run it on a Windows PC (no hosting account)

Double-click `laptop/start-relay.bat`. It installs Node.js and `cloudflared` with winget if they are missing, starts the relay on `127.0.0.1:8080` (reachable only through the tunnel), opens a free Cloudflare quick tunnel and prints and copies a `wss://…trycloudflare.com` address. Enter that address in the app under Settings > Internet ride.

- The address changes every time you start it. Enter the new one on every phone.
- The PC must stay on, awake and online while you ride. The script asks Windows not to sleep while it runs, but closing the lid may still sleep a laptop.
- Cloudflare carries your riders' encrypted traffic, so it sees the same things the relay sees (IP addresses, room id, packet size and timing), and never the audio. For a fixed address, use a named Cloudflare tunnel with your own domain or deploy to a host (see above).
- Not tested on a real Windows PC yet.

`HOST` (default `0.0.0.0`) sets the address the relay binds to.
