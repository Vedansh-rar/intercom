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
