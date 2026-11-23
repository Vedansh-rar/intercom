# Rider Intercom

**Talk to your riding buddy without a dedicated intercom — no cell signal needed.**

Most riders don't own a dedicated helmet intercom. And even a phone call won't help when you're riding through mountains, forests, or rural stretches with no reception. Wind noise and engine roar make shouting impossible. This app turns two Android phones into a direct rider-to-rider voice link over Bluetooth — no internet, no cell tower, no cloud service, no subscription. Just pair, tap, and talk.

> **[Download APK](../../releases/latest)** — Android 10+ required. Install on both phones. (APKs are published on the Releases page; see `RELEASING.md`.)

---

<p align="center">
  <img src="screenshots/02_home_ready.png" width="230" alt="Home — ready to host" />
  &emsp;
  <img src="screenshots/04_hosting.png" width="230" alt="Hosting — waiting for buddy" />
  &emsp;
  <img src="screenshots/03_settings.png" width="230" alt="Settings" />
</p>
<p align="center">
  <em>Home screen</em> &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;
  <em>Active call</em> &emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;
  <em>Settings</em>
</p>

---

## How to use

1. **Install** the app on both phones.
2. **Pair** the phones via Bluetooth (standard pairing in phone settings).
3. **Grant permissions** when prompted — microphone, Bluetooth, and notifications.
4. **Rider A** taps **Host Ride** → **Start hosting**. The phone listens for a buddy.
5. **Rider B** taps **Join Rider** → selects the host from paired devices.
6. **Talk.** The channel is always open — just speak and your buddy hears you.

### Group ride (three or more riders)

1. On every phone, set the **same ride code** and switch **Group ride** on (Start screen). Group rides are always encrypted; there is no unencrypted option.
2. **One rider taps Host Ride → Start group ride.** Everyone else taps **Join Rider → Join group ride**. There is no pairing and no paired-phone list: phones find each other through Google Nearby Connections.
3. The call screen lists every rider with a speaking indicator, a link-quality label (good / fair / poor), whether they are direct or reached through other riders, and a **Mute** button. Mute affects only your own helmet.

Needs Google Play services. Two riders can leave Group ride off and nothing changes for them.

### Distance to your buddy (optional)

**Settings → Location → Share my location with riders** (off by default). When it is on, each rider sees how far away the other is (`240 m`, `1.3 km`, or `< 20 m` when the two GPS fixes cannot tell them apart), an arrow showing which way relative to where you are heading, and "ahead" or "behind" once you are moving. If the other rider's position stops arriving, the number turns grey after about 10 s and says "last seen 12 s ago". In a group each rider's row shows their distance. An optional beep warns you when a rider stays farther than a distance you set for a time you set.

- The phone asks for location permission only when you switch this on, never at launch. Precise location is needed; approximate is not enough for a distance.
- Your position goes only to the riders in your call, over the same encrypted phone-to-phone link as the voice. There is no server and no network request, nothing is stored, and the GPS stops when the call ends. On a call you chose to run unencrypted, positions are never sent.
- You see distances only while you share your own: if sharing is off nothing is read, sent or kept, and positions sent to you are ignored.
- The arrow uses your direction of travel, not the phone's compass, so it works in a tank bag or pocket, and it needs you to be moving (about 5 km/h or more). Standing still it shows a compass word instead.

### Rider controls and safety

- **Mute and Deafen** are on the call screen and in the notification. Mute stops your mic; Deafen stops what you hear and also mutes you. Your buddy sees "muted" or "deafened". The state belongs to the call and resets when it ends.
- **Headset and remote buttons** can each be set to push to talk, mute, deafen, noise reduction, end call, or nothing (**Settings → Remote buttons**). Defaults: double press push to talk, triple press mute, long press end call. Any Bluetooth media-key device works. Volume buttons can be used too, but only if you switch that on, because while a call runs they then stop changing the volume.
- **Music** lowers (it is not paused) while your buddy speaks and comes back after. A setting also lowers it while you speak.
- **SOS** needs a deliberate act: tap the red button twice, or hold it for three seconds. It sends your last known position if you share location, otherwise just the alert, and repeats it twice because there is no acknowledgement. The other phone shows a full-screen banner and plays a siren until dismissed, even if muted or deafened. The position goes only over the phone-to-phone link and is never put in a notification. A buddy on an older build cannot receive it, and the button says so.
- **Link loss**: a short falling tone when the link drops, a rising one when it returns, while the app keeps retrying on its own. **Reconnect to last buddy** appears on the start screen.

### Talk modes

| Mode | How it works |
|------|-------------|
| **Open call** (default) | Voice-activated. Just speak — the detector opens the channel. First speaker holds the floor; the other side is muted until they stop. |
| **Push to talk** | Hold the on-screen button, the notification button, or a Bluetooth headset button to talk. For helmets too noisy for the voice detector. |

### Microphone options

Pick what suits your helmet in **Settings → Microphone**:

- **Phone mic** — simplest. Works at town speeds; wind beats it on the highway.
- **Wired boom mic** (USB-C / 3.5 mm) — best wind rejection. Headset stays on A2DP so music stays stereo.
- **LE Audio headset** — mic + hi-fi output at the same time. Android 13+; few helmet headsets support this yet.
- **Headset mic** — convenient, but Android drops music to mono while the mic is open.

### First run, languages and riding comfort
The first launch walks you through the permissions, the battery settings that keep a call
alive on your phone brand, and a test call that plays your voice back through the headset.
Run it again from Settings. The app is available in English and Hindi (Settings > Language,
or follow the phone); the ride-code words stay English so both phones always match. High
contrast and large buttons for gloves are in Settings too.

### Diagnostics and ride summary
Settings > Share log saves the recent app log with positions, ride codes, names and
Bluetooth addresses removed and opens the share sheet; nothing is uploaded. After a call a
summary shows duration, link quality and radio switches. It is kept in memory only.

### Internet ride (optional, for city and plains riding)
Bluetooth and Wi-Fi Direct reach tens of metres, and a group ride a few hundred along a
convoy. Where there is phone signal you can ride further apart over mobile data instead:
Settings > Internet ride, enter the address of a relay server, switch it on, and start a group
ride with a five-word code. The relay is a small Node server in `server/` that **you run**
(`server/README.md` has the steps for Fly.io, Render or a VPS); the app has no built-in server
and sends nothing anywhere until you set one. It forwards packets between riders and cannot
read them: every rider pair runs the ride-code handshake through it and encrypts with AES-GCM,
exactly as on the other radios. It does not work without signal, and it uses about 16 MB of
mobile data per hour of talking for each rider listening. Rider positions and distance are not
sent on internet rides.

### Updates (optional)
Settings > Updates checks GitHub Releases for a newer version, but only when you tap
**Check for updates** (or switch on the once-a-day check, which is off by default and only
looks; it never downloads by itself). A newer version is offered only if it is strictly
higher by semantic-version rules, so you are never offered a downgrade. Downloading is a
separate tap: the file is streamed to disk with progress and a Cancel button, its SHA-256 is
computed as it arrives and compared with the checksum published for that APK, and a
mismatch, wrong size, wrong package, lower version code or different signing key deletes the
file and refuses. Installing is a third tap and goes through Android's own installer. Updates
are refused while a call is running, because installing ends the call.

## What works

- Direct voice calls over Bluetooth RFCOMM — no internet, no server
- Automatic fallback to Wi-Fi Direct for longer range
- Voice activity detection — the channel opens when you speak
- Half-duplex floor arbitration — one speaker at a time, no crosstalk
- IMA ADPCM codec at 16 kHz / 20 ms frames (low latency, low bandwidth)
- Opus voice codec (16 kHz, 24 kbps, in-band FEC, adaptive 24/16/12 kbps) on a native Oboe audio path, falling back to ADPCM with older builds and to AudioRecord/AudioTrack if Oboe fails
- Ride code and encryption: both phones share a passphrase (4-8 words, or a 6-digit number) that is never sent over the air. After connecting they prove they know it with a challenge-response, then every voice and control frame is encrypted and authenticated with AES-256-GCM. A wrong code closes the link with "Wrong ride code"; repeated wrong codes are throttled. An older buddy without encryption is refused unless you allow it in Settings, and the call screen then shows a "NOT ENCRYPTED" warning
- Group rides (opt-in): Nearby Connections (`P2P_CLUSTER`) carries a convoy. Every neighbour is authenticated with the same ride-code handshake before any audio is accepted. Frames are relayed up to 3 hops with duplicate suppression, each rider is decoded separately and mixed with soft clipping (at most 3 voices at once), and the floor gives the host priority while other speakers are ducked rather than cut off
- Rider distance (opt-in): GPS position shared only phone to phone, encrypted, with a smoothed distance, direction arrow, ahead/behind, staleness and an optional falling-behind beep
- Spectral noise suppression (Off / Light / Medium / Strong) — cleans wind and engine noise from your voice. Optional "AI noise reduction" (RNNoise) that falls back to the spectral one by itself if the phone is too slow, an optional Auto mode that picks the strength from GPS speed, an adjustable wind low-cut (80-400 Hz, or off; default 150 Hz) and an optional slow AGC with a limiter
- Foreground service — call stays alive with the screen off
- Headset button control (play/pause to cut in)
- RTT measurement and clock sync between phones
- BLE nearby-host discovery — find hosting phones without manual pairing
- HFP Audio Gateway — bridges with helmet Bluetooth intercoms
- Automatic transport switching when signal degrades (no manual intervention)

## Security: what the ride code does and does not protect

- The code is never transmitted, and neither is a hash of it. The handshake sends two random nonces and two HMAC-SHA256 proofs; the AES key is derived from the code with PBKDF2-HMAC-SHA256 (150,000 iterations) salted per connection with both nonces and both session IDs.
- **Not a PAKE.** The brief asks for SPAKE2 or similar if a small, well-maintained library exists. I did not find one I could responsibly add, and hand-writing one is ruled out, so this is the stated fallback: PBKDF2 plus a rate limit. The consequence is real: someone who *records* a handshake can test guesses against it offline. Their cost per guess is 150,000 HMACs and cannot be shared between recordings (the salt is different every time), but the code's own strength is what matters. Five words is 40 bits, which is reasonable; **a 6-digit number is about 20 bits and falls to a recorded handshake quickly**. The app says so when you pick a number. Use words when it matters.
- The rate limit stops someone standing next to you from guessing live (free for two mistakes, then 15 s, doubling to 5 min). It does nothing against offline guessing.
- Each connection (first connect, reconnect, transport switch) derives fresh keys, so keys last one connection.
- PING/PONG, END and SWITCH are not encrypted (they carry no voice or position), so someone who can inject into the link could end a call. They cannot listen or inject audio.
- An intruder who connects to a waiting host and fails the code closes that host's waiting link, as the spec requires ("close the link"); the host has to start again. Group mode (a later phase) is the place to fix that.
- **Group mode is weaker in three ways you should know about.** (1) *Relays are trusted.* Each hop is encrypted, but a phone that relays your voice decrypts it first, so every rider who passes your audio along can hear it. That is the same trust the shared ride code already implies, but it is not end-to-end. (2) *Sender IDs are not authenticated.* Anyone who holds the ride code can claim another rider's ID in the mesh header and speak as them. (3) *The failed-code lockout is shared.* Someone outside the group who guesses wrong codes repeatedly can lock out riders who are trying to join, until the delay expires.
- The ride code is stored in its own preferences file, excluded from Android backup and device transfer. It is not hardware-keystore protected: a rooted phone can read it.
- QR scanning uses Google's code scanner from Play services, which draws the camera screen itself, so the app needs no camera permission. That module is downloaded by Play services, not by this app. On a phone without Play services, type the code.

## Privacy and permissions

- No analytics, no accounts, no ads, no tracking. Voice and positions travel only phone to phone.
- **There are two optional network uses, both off until you switch them on.** (1) The update check (Settings > Updates): HTTPS to `api.github.com` and GitHub's download hosts, nothing else, never at launch unless you switched on the daily check. (2) Internet rides (Settings > Internet ride): a TLS WebSocket to the relay address you entered, only while an internet ride is running. Each request carries what any web request does, such as your IP address; the app sends no identifier and no settings, and redirects to other hosts are refused. The relay's owner can see your IP address, when you talk and how much, but not the voice, names or positions (end-to-end encrypted). The room it sees is a slow hash of your ride code, so use a five-word code: the app refuses 6-digit numbers for internet rides.
- Microphone: your voice. Bluetooth and nearby devices: finding and connecting to your buddy. Notifications: the ongoing-call notification. Location (only if you switch on distance): the GPS fix shared with riders in your call. Wi-Fi state and nearby Wi-Fi devices: Wi-Fi Direct.
- **Install unknown apps** (`REQUEST_INSTALL_PACKAGES`): used only to hand a downloaded and verified update to the system installer. Android asks you to allow it for this app the first time; you can switch it off again afterwards.

## Known issues

- **Internet rides have not been run on real phones or against a deployed relay.** The relay server is tested (16 tests: rooms, routing, limits, rate limits, ping timeouts) and the app's internet link is tested against an in-memory copy of it (handshake, wrong code, encryption, reconnect, refusal), but real mobile networks are not: carrier NAT, handovers, how long a reconnect takes after a tunnel, battery and data use, and how audio sounds with 100 to 300 ms of latency. Latency is higher than over radio, and the app does not measure or adapt to it. Everything is in the Phase 10 section of `docs/DEVICE_TEST_CHECKLIST.md`. There is no default relay: you have to host one.

- **The update checksum is not a signature.** It is published next to the APK on the same release, so it catches a damaged or truncated download, but anyone who could edit the release could change both files. The protection against a swapped APK is Android's own rule that an update must be signed with the same key as the installed app; the app also checks this before opening the installer so you get a clear message. If the signing key ever changes (key rotation), the in-app update will refuse and a manual reinstall is needed. None of this has been run against a real release on a phone; see the Phase 9 section of `docs/DEVICE_TEST_CHECKLIST.md`. The installed build has `versionCode` 1, so the first release must use a higher one (see `RELEASING.md`).


- **Rider distance has not been run on real phones.** The maths, send-rate policy, staleness, smoothing and message handling are unit-tested, including positions relayed across a simulated group. The GPS itself, the permission prompts on each Android version, the location foreground-service type, battery cost and whether the numbers match the road are in `docs/DEVICE_TEST_CHECKLIST.md`. Known limits: the ahead/behind and arrow need you to be moving (a stationary GPS has no heading); GPS accuracy is tens of metres in cities and under trees, and a distance under about 20 m is shown as `< 20 m` on purpose; a rider who shares sees distances only if they share too.

- **Group mode has not been run on real phones.** It is unit-tested (relay and de-duplication rules across simulated multi-phone meshes, per-rider mixing, floor and priority, the secure channel handshake), but nothing here proves how Nearby Connections behaves with no internet, whether a hop adds more delay than the 200 ms jitter budget allows, how a mid-range phone copes with three Opus decoders, or whether discovery works with the `neverForLocation` Bluetooth-scan flag. Each of these is a line in `docs/DEVICE_TEST_CHECKLIST.md`. The age of a relayed audio frame is estimated (time held at each relay plus 15 ms per hop) because the phones' clocks are not synchronised.

- **Audio quality (wind and noise) has not been heard on a bike.** The wind low-cut, AGC, limiter, cost monitor and Auto policy are unit-tested on synthetic signals, and the RNNoise wrapper was checked on a desktop (noise-only input down 56 dB, voice delayed 11 ms, 0.3 ms per 20 ms frame, about 1.6% of one core). **The cost on a phone has not been measured**: a mid-range phone is guessed to be 4 to 8 times slower, which is why the spectral denoiser stays the default, the AI option is clearly labelled as heavier, and the app drops back to the spectral denoiser on its own if the AI one averages over 8 ms per 20 ms frame. RNNoise has no strength setting, so Light/Medium/Strong do not apply to it. Whether it sounds better than the spectral denoiser in real wind is the first line of the Phase 6 section in `docs/DEVICE_TEST_CHECKLIST.md`. RNNoise is BSD-licensed; see `docs/THIRD_PARTY_NOTICES.md`.

- **Noise suppression presets**: fixed in code. The selector now offers Off / Light / Medium / Strong (Medium did not exist before, which is why the README and the app disagreed), each preset maps to distinct denoiser parameters (unit-tested), and re-enabling after Off no longer replays stale audio. Needs a ride to judge how the presets sound; see `docs/DEVICE_TEST_CHECKLIST.md`.

- **Wi-Fi Direct joiner path**: reviewed and fixed by reading (the joiner now binds the voice port so it can hear the host, retries the control connection instead of restarting discovery, uses the reported group-owner address, and stops re-issuing `connect()` over one in flight). The retry/pick/gate logic is unit-tested; the radio handshake itself has still **not** been run on two phones.

## How it works

```
Phone A                                    Phone B
┌─────────────┐     Bluetooth / Wi-Fi Direct     ┌─────────────┐
│  Mic → ADPCM ├────────────────────────────────►│ ADPCM → Spk │
│  Spk ← ADPCM │◄────────────────────────────────┤ Mic → ADPCM │
└─────────────┘                                  └─────────────┘
         No internet. No cell signal. Direct radio link.
```

In a group ride the picture changes only underneath: each phone holds one Nearby Connections link per neighbour (up to 6), wraps every message in a 13-byte mesh header (hops left, random 32-bit sender ID, sequence, age), and forwards what it receives to its other neighbours. The microphone, codec, jitter buffer and player are the same ones; the player just reads from a mixer instead of a single jitter buffer.

The two phones connect directly over Bluetooth Classic (RFCOMM) or Wi-Fi Direct — both are device-to-device radios that work without any network infrastructure. Voice is compressed with IMA ADPCM (4-bit per sample, 32.6 kbps) and sent in 20 ms frames. A spectral denoiser strips wind and engine noise before transmission. The app monitors link quality and switches between Bluetooth and Wi-Fi Direct automatically if the signal degrades.

## Related

This intercom module has been integrated into the [KTM Nav Gen3](https://github.com/Pavanayi1/KTM-Nav-GEN3) app for a combined motorcycle dashboard navigation + rider intercom experience. See that repo for the full bike control setup.

## Building from source

```bash
git clone https://github.com/Pavanayi1/intercom.git
cd intercom

./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Requirements**: Android Studio, JDK 17, Android SDK 35, min SDK 29 (Android 10).

## Project structure

```
app/src/main/java/com/intercom/app/
├── audio/          # codecs, mic capture, wind low-cut, spectral and AI denoisers, AGC, jitter buffer, player
├── call/           # CallController, IntercomService (foreground service)
├── discovery/      # Host beacon & scanner (BLE nearby discovery)
├── floor/          # Voice activity detector, half-duplex floor arbiter
├── hfp/            # HFP Audio Gateway (helmet intercom bridge)
├── media/          # Media button handling, audio focus
├── protocol/       # Wire protocol codec & message types
├── transport/      # RFCOMM, Wi-Fi Direct, LinkSelector, SwitchPolicy
├── ui/             # Compose UI screens
└── util/           # Permissions, preferences, OEM quirks
```

## License

Private — not for redistribution.
