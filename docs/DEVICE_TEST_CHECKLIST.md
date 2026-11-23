# Device test checklist

Everything here needs real hardware and has **not** been verified. Items are added as each
phase lands. Mark an item only after testing it on a phone, and note the model and Android
version.

## Phase 0

- [ ] Noise presets: Light / Medium / Strong audibly differ on a real call; switching live
      (Settings and the notification action) takes effect without restarting the call.
- [ ] Switching Off -> a preset mid-call produces no click or replayed audio.
- [ ] Wi-Fi Direct joiner: joiner hears the host (voice port fix) and host hears joiner.
- [ ] Wi-Fi Direct joiner: connects when the host's listener comes up a few seconds after
      the group forms (control connect retry).
- [ ] Wi-Fi Direct joiner: second `connect()` does not cancel an invitation in progress.
- [ ] Wi-Fi Direct: closing and restarting a call quickly does not hit "address in use".
- [ ] Debug build: `adb forward tcp:9333 tcp:9333` + `tools/mac_intercom.py` still works.
- [ ] Release build: nothing is listening on port 9333 (`adb shell ss -ltn`).
- [ ] Release signing with `keystore.properties`: APK installs and verifies with
      `apksigner verify`.

## Phase 2 (Opus + Oboe)

None of this has run on a phone. CI proves it compiles for arm64-v8a and armeabi-v7a and
that the pure-Kotlin logic passes; libopus and Oboe themselves are untested.

- [ ] Two phones on a v2 build negotiate Opus: stats line shows `codec=Opus@24000` and
      `io=oboe/oboe` on both.
- [ ] Opus voice quality is audibly better than ADPCM at the same distance; no clicks,
      robotic artefacts or doubled audio.
- [ ] Round-trip with real libopus (no JVM test can do this): speech in one phone is
      intelligible on the other, and `undecodable` and `codecMismatch` stay at 0.
- [ ] v2 phone with an older v1 build: call works on ADPCM, `codec=Adpcm`, no errors on
      either side.
- [ ] Loss concealment: walk to the edge of range. `fecPlc` rises, speech degrades
      gracefully instead of stuttering.
- [ ] Adaptive bitrate: at the edge of range `codec=Opus@16000` / `@12000` appears, and
      returns to 24000 after about 15 s of clean link. Does not oscillate.
- [ ] Latency: mouth-to-ear delay is not worse than the AudioRecord/AudioTrack path.
- [ ] Output underruns: `trackUnderruns` stays low over a 30 minute call on a mid-range
      phone (the 60 ms output cushion is a guess).
- [ ] Bluetooth headset stays on A2DP with Oboe capture (music stays stereo): capture uses
      VOICE_RECOGNITION on purpose, not VoiceCommunication.
- [ ] Unplug a wired/USB headset mid-call: status shows "Microphone changed -
      reconnecting", audio resumes on the phone mic or headset, no crash. Same for
      Bluetooth headset disconnect and reconnect (output side).
- [ ] Hidden legacy path: long-press the Settings title, start a new call, stats line shows
      `io=audiorecord/audiotrack` and the call works.
- [ ] Platform noise suppressor still attaches with Oboe's session id (`hwfx=` in the stats
      line on a phone that has one).
- [ ] CPU and battery with Opus + denoiser on a low-end phone over a long call.
- [ ] Oboe failure fallback: on a phone where Oboe cannot open, the call still starts on
      AudioRecord/AudioTrack.

## Phase 3 (ride code, authentication, encryption)

The crypto and the handshake state machine are unit-tested on the JVM (`AuthSessionTest`,
`FrameCipherTest`, `FrameGateTest`). What no test can show is how it behaves on real radios
and phones.

- [ ] Two phones with the same ride code connect over Bluetooth: call screen says
      "Encrypted with your ride code" on both, within a second or two of connecting.
- [ ] Same over Wi-Fi Direct.
- [ ] Different ride codes: both phones show "Wrong ride code" and the link closes. Neither
      phone plays any audio.
- [ ] Three wrong attempts in a row: the next try is refused with "Try again in N s", and
      the delay grows. A correct code afterwards clears it.
- [ ] Time to "Encrypted": PBKDF2 (150,000 iterations) runs on every connect. Measure on a
      low-end phone; if it is over about 1.5 s, lower `KeyDerivation.DEFAULT_ITERATIONS`.
- [ ] Transport switch mid-call (Bluetooth to Wi-Fi Direct and back): the call re-secures
      after the switch and audio resumes. Note the gap; it includes a new key derivation.
- [ ] Walk out of range and back (reconnect): re-authenticates, audio resumes, no stale
      audio from before.
- [ ] Phone with an older (v1) build: refused by default with a clear message. With
      Settings > "Allow an unencrypted buddy" on, the call works and the red NOT ENCRYPTED
      banner is on screen for the whole call.
- [ ] Ride code survives restarting the app; it is not in the backup (`adb shell bmgr`
      or check that `rider_intercom_secret.xml` is excluded).
- [ ] Type a code with a typo in a word: the field names the bad word before connecting.
- [ ] QR: "Show QR" on one phone, "Scan QR" on the other fills in the code. On a phone
      without Google Play services the scan fails with a message and typing still works.
- [ ] Battery/CPU of AES-GCM at 50 frames a second on a low-end phone over a long call.
- [ ] `adb logcat -s IntercomStats`: `sec=Encrypted`, `rejected=0` and `cleartextDropped=0`
      on a healthy call.
- [ ] Packet capture on a laptop sniffing the link (if you can): payloads are not
      recognisable audio, and the ride code does not appear anywhere in the capture.
