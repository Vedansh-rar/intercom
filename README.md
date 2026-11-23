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

## What works

- Direct voice calls over Bluetooth RFCOMM — no internet, no server
- Automatic fallback to Wi-Fi Direct for longer range
- Voice activity detection — the channel opens when you speak
- Half-duplex floor arbitration — one speaker at a time, no crosstalk
- IMA ADPCM codec at 16 kHz / 20 ms frames (low latency, low bandwidth)
- Opus voice codec (16 kHz, 24 kbps, in-band FEC, adaptive 24/16/12 kbps) on a native Oboe audio path, falling back to ADPCM with older builds and to AudioRecord/AudioTrack if Oboe fails
- Ride code and encryption: both phones share a passphrase (4-8 words, or a 6-digit number) that is never sent over the air. After connecting they prove they know it with a challenge-response, then every voice and control frame is encrypted and authenticated with AES-256-GCM. A wrong code closes the link with "Wrong ride code"; repeated wrong codes are throttled. An older buddy without encryption is refused unless you allow it in Settings, and the call screen then shows a "NOT ENCRYPTED" warning
- Spectral noise suppression — cleans wind and engine noise from your voice
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
- The ride code is stored in its own preferences file, excluded from Android backup and device transfer. It is not hardware-keystore protected: a rooted phone can read it.
- QR scanning uses Google's code scanner from Play services, which draws the camera screen itself, so the app needs no camera permission. That module is downloaded by Play services, not by this app. On a phone without Play services, type the code.

## Known issues

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
├── audio/          # ADPCM codec, mic capture, jitter buffer, denoiser, player
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
