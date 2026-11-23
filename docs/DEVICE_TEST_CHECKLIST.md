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
