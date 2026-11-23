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

## Phase 4 (group mode)

The relay rules, de-duplication, per-rider mixing, floor and priority, peer bookkeeping and
the per-connection secure channel are unit-tested on the JVM, including an in-memory
multi-phone simulation (`GroupNodeTest`). The Nearby Connections link itself (`GroupLink`),
the Android permissions, and everything about timing on a real radio can only be checked on
phones. Needs three phones for most items; four or five for the relay items.

Setup: same ride code on all phones, Group ride switched on, one phone taps Host Ride, the
rest Join Rider.

Discovery and connection
- [ ] With no SIM and Wi-Fi/mobile data off on every phone, three phones find each other and
      all show the other two in the rider list. Record how long this takes.
- [ ] Android 12/12L phone: the group works after granting the permissions the app asks for
      (including Location). Android 13+: works without Location.
- [ ] BLUETOOTH_SCAN is declared `neverForLocation`: confirm Nearby discovery still finds
      peers on Android 12+. If it does not, the flag has to come off for group mode.
- [ ] A phone without Google Play services shows "Group mode needs Google Play services" and
      does not crash; two-rider calls still work on it.
- [ ] A joiner started before the host: it keeps looking and joins when the host starts.
- [ ] Two phones that both see each other at the same instant do not end up with two
      connections (look at the "direct" count in `adb logcat -s IntercomStats`).

Security
- [ ] A phone with a different ride code never appears in anyone's list and plays no audio;
      it shows "Wrong ride code". The others keep talking.
- [ ] Wrong codes three times in a row from an outside phone: a rider trying to join then
      gets "Try again in N s" (expected, documented limitation).
- [ ] No ride code set: Start group ride is disabled.
- [ ] Packet capture, if possible: nothing recognisable as audio, no ride code.

Audio and floor
- [ ] Three phones, one speaks: the other two hear it, and the rider list marks that rider
      "Speaking" on both.
- [ ] Two riders speak at once: one wins, the other is heard quietly and sees "Wait for them
      to finish". The host speaking takes the floor from a joiner mid-sentence, and the joiner's
      phone stops transmitting.
- [ ] Mute a rider: silent on this phone only; the others still hear them; a rider who is
      behind you (reached through your phone) still hears them too.
- [ ] Speech quality with 2 and 3 people talking over each other: soft clipping, no harsh
      distortion. Gain per rider is not in the UI yet.
- [ ] CPU and battery with three simultaneous Opus decoders on the weakest phone you have,
      screen off, 30 minutes.
- [ ] Opus phone and ADPCM-only phone in the same group hear each other (each decodes the
      codec in the frame).

Relay and range
- [ ] Line of 4 phones A-B-C-D with A and D out of each other's range: D hears A. Measure the
      delay A to D by recording both; compare with the 200 ms budget.
- [ ] A 5th phone beyond three hops hears nothing from A (the hop limit holds).
- [ ] Walk the middle phone away: the far phones lose each other, the rider list updates
      within about 15 s, and the floor is not stuck on a rider who left.
- [ ] Bring the middle phone back: riders reappear without restarting the app.
- [ ] Switch a rider's phone off mid-sentence: floor is released within about 2 s.
- [ ] `adb logcat -s IntercomStats`: `relayed`, `dropped`, `stale` and `overCap` counts look
      sane, and `stale` stays near zero in a normal group.
- [ ] Nearby delivers payloads reliably and in order, so one lost packet can stall the ones
      behind it. Walk to the edge of range with a 3-phone group and listen for bursts of
      delayed audio.

Regression
- [ ] Two phones with Group ride off: Bluetooth and Wi-Fi Direct calls behave exactly as in
      Phase 3 (connect, encrypted, switch transports).

## Phase 5 (rider position and distance)

The geometry (haversine, bearing, ahead/behind), the send-rate policy, staleness, smoothing,
the falling-behind rule, the payload encode/decode, the "off means off" switch and the group
relay of positions are unit-tested on the JVM. What cannot be tested without phones is the
GPS itself, the permission prompts, the foreground-service type, battery, and whether the
numbers match the road. Needs two phones; three for the group items.

Permission flow (do each on its own Android version if you have them)
- [ ] Fresh install: Start screen, microphone and Bluetooth prompts appear exactly as before.
      **No location prompt at launch** on Android 13+ (and none until a ride starts on 12/12L
      where Nearby needs it).
- [ ] Settings → Location → switch on "Share my location": the system prompt appears **now**.
      Allow "Precise": the switch stays on. Allow "Approximate" only: the switch goes back
      off and the app says precise location is needed. Deny: same.
- [ ] Android 12 and 13, 14, 15: the prompt shows the precise/approximate choice (the app
      requests both permissions together, which Android 12+ requires).
- [ ] Revoke location in system settings while sharing: the app returns to "not allowed",
      nothing crashes, the call and audio carry on.
- [ ] "Allow only while using the app" is enough for a ride with the **screen off**, because
      the call's foreground service declares the location type. Confirm: lock the screen for
      5 minutes mid-ride and distances keep updating.
- [ ] Android 14 and 15: switching sharing on **mid-call** adds the location type to the
      running service without ending the call (watch `adb logcat -s IntercomService`;
      "startForeground failed" must not appear). Switching it off drops the type.
- [ ] Android 14+: starting a ride with sharing on and location denied does not crash the
      service (the location type is only declared when the permission is granted).
- [ ] The status-bar location indicator is on only while a call is up and sharing is on.

GPS and numbers
- [ ] Two phones side by side outdoors: distance reads "< 20 m" (not "0 m" or a flicker).
- [ ] Walk one phone 100 m, 250 m, 500 m along a street and compare with a map or a measuring
      app. Expect agreement within the stated accuracy (a few tens of metres at worst).
- [ ] Distance stops flickering between two values from one report to the next (the
      smoothing works), but a phone driven away fast does not lag behind reality.
- [ ] Cold start: sharing on with no fix since morning. Record how long "Looking for a GPS
      fix" lasts outdoors and indoors. Indoors it may never get one; it must say so, not
      show a wrong number.
- [ ] Phone with location switched off in quick settings: the app says so, and recovers when
      it is switched back on.
- [ ] A phone with no Google Play services falls back to the plain GPS provider and still
      shows distance.
- [ ] Airplane mode and no SIM: distance still works (GPS needs no network).

Direction and wording
- [ ] Riding (or walking fast, over ~5 km/h): the arrow points at the other rider relative to
      the way you are facing; turn a corner and it turns the opposite way. "Ahead" and
      "behind" read correctly when one rider is in front of the other.
- [ ] Standing still: no arrow (a stationary GPS has no heading); a compass word is shown
      instead ("to the NE") and "ahead / behind" is not claimed.
- [ ] Phone in a tank bag or pocket at different orientations: the arrow does not depend on
      how the phone is held (it uses movement direction, not the compass).
- [ ] km/mi setting switches the display, and a phone set to a US/UK locale starts on miles.

Freshness
- [ ] Switch the other phone's GPS off (or walk it into a building): within about 10 s the
      value turns grey and says "last seen N s ago", counting up; it recovers on its own.
- [ ] Your own GPS having no fix is blamed on your phone ("your GPS signal is weak"), not on
      the other rider.
- [ ] A reconnect (walk out of range and back): the other rider's distance appears within a
      second or two of the link coming back (positions are sent immediately on connect).
- [ ] Clock offset: with the two phones' clocks several seconds apart (set one manually),
      "last seen" still counts from about zero when a report arrives.

Group
- [ ] Three phones: each rider's row shows a distance to every other rider who shares, also
      through a relay (A to C via B).
- [ ] A rider who is not sharing shows no distance and the others are not affected.
- [ ] A rider muted on your phone still shows a distance.
- [ ] Walk one rider away: their row greys out after about 10 s and disappears with them.
- [ ] Position traffic does not disturb audio in a group (compare dropped/stale counters in
      `adb logcat -s IntercomStats` with sharing on and off).

Alert
- [ ] "Warn me when a rider falls behind": set 200 m for 10 s, walk one phone out. One beep
      about 10 s after crossing 200 m, then again every 30 s while still out, and silence
      once back inside ~180 m. A GPS wobble across 200 m for a second does not beep.
- [ ] The beep is local only: the other rider hears nothing.
- [ ] A rider who disappears completely does not trigger it (that shows as "last seen").

Privacy and security
- [ ] Sharing off (the default): `adb logcat` shows no location provider activity; the status
      bar shows no location indicator; the other phone sees no distance.
- [ ] Only one phone sharing: that phone sends, the other discards, and neither shows a
      distance (you see distances only while you share your own).
- [ ] Call with "Allow an unencrypted buddy" on: no position is sent.
- [ ] A buddy on the Phase 1-3 build (no position support): the call works as before and no
      position frames appear (logcat shows none sent).
- [ ] Packet capture, if possible: position frames are not readable (no recognisable
      coordinates in the capture).
- [ ] After "End call", the location indicator goes away and GPS stops (battery stats).

Battery
- [ ] One hour screen-off ride with sharing on versus off on the same phone. Record the
      difference; the update interval is 1-5 s depending on speed and spacing.

## Phase 6 (audio quality: wind and noise)

What the JVM tests cover: the wind low-cut (default bit-identical to the old fixed filter,
attenuation and passband on synthetic tones, off, steep, clamp, live change), the slow AGC and
limiter (silence stays silent, sub-gate noise is untouched, slow rise and faster fall, bounded
gain, clipping input never passes the ceiling), the AI cost monitor, and the Auto preset policy
(hysteresis, dwell, losing the fix). What they **cannot** cover is RNNoise itself (the native
library is ARM-only in CI), how any of this sounds, or what it costs on a phone.

The numbers below were measured on a desktop x86 core, not a phone: RNNoise plus the 16/48 kHz
resampling took about 0.3 ms per 20 ms frame (1.6% of one core). A mid-range phone is
guessed to be 4 to 8 times slower, so 6 to 12% of a core. **That guess is not a measurement;
record the real figure here.**

AI noise reduction cost (the important one)
- [ ] Mid-range phone (the slowest you have): Settings, Noise reduction engine, AI. Start a
      call. `adb logcat -s IntercomStats` prints `aiUs=` (mean microseconds per frame, over
      100 frames). Write it down: ______ us. Under 8000 is within budget; under 2000 is
      comfortable.
- [ ] Ten minutes of a call on Standard, then ten on AI, same phone, same screen-off. Compare
      the battery drop (Settings, Battery) and the phone's temperature.
- [ ] Force the fall-back: on the slowest phone you have, run another heavy app, or throttle
      with `adb shell cmd thermalservice override-status 3`. After a few seconds the status
      line shows "AI noise reduction is too heavy for this phone - using standard", the engine
      in Settings is back on Standard, and the call never drops or stutters while it happens.
- [ ] A phone whose native library does not load (an emulator, an odd ABI): choosing AI says
      "not available on this phone - using standard" and the call carries on.
- [ ] Switching Standard to AI and back mid-call, several times: no click, no burst of noise,
      no stale audio from before the switch.
- [ ] AI engine with the noise setting Off: the voice goes out with only the low-cut and gate.

AI voice quality (needs a ride)
- [ ] Same stretch of road, same helmet, both engines: record your own voice on the buddy's
      phone and listen. Does AI cope better with gusts and passing traffic? Does it chop word
      starts or make the voice sound "watery"? Does it fall over with the visor open?
- [ ] Engine noise at a steady highway speed: Standard Strong versus AI.
- [ ] A rider with a quiet voice or a strong accent: does AI remove consonants?
- [ ] The 16 kHz to 48 kHz and back resampling adds about 11 ms and a gentle filter at 7 kHz.
      Confirm the delay is not noticeable in conversation and there is no whistle or ringing.

Wind low-cut
- [ ] Default 150 Hz, standard slope: sounds exactly as before this update.
- [ ] Move the slider across its whole range during a call: the change is heard within a
      moment, with no click and no dropout.
- [ ] Raise it to 250 to 300 Hz on a windy bike: less thump at the buddy's end. Is the voice
      thin? Note the best value for your helmet: ______ Hz.
- [ ] "Steeper slope" on and off: audible difference, no ringing.
- [ ] Off: the rumble comes back (this is only for a quiet situation or a mic that already
      filters).

Even out my voice (AGC)
- [ ] Off by default and the voice is unchanged from before.
- [ ] On: speak quietly for 10 seconds, then normally. The buddy hears the level rise over a
      few seconds, not in jumps. No "pumping" (a rising and falling wind noise between words).
- [ ] Silence and wind-only gaps between sentences are not boosted. Check specifically at a
      steady highway speed with the visor shut; if wind comes up between words, the gate at
      -45 dBFS is too low for that helmet (tell me the number).
- [ ] Shout, tap the mic, or bump the boom: no clipping or crackle, and the level recovers
      within a second or so.
- [ ] Combined with AI noise reduction and with Strong standard: no instability.

Auto preset (needs location sharing on)
- [ ] Settings, Auto on, location sharing on: ride from a standstill up to 80 km/h and back.
      The preset (visible in `adb logcat -s IntercomStats`, `ns=`) moves Light, Medium,
      Strong only after the speed has held about 10 seconds, and back down with a few km/h of
      slack. Thresholds are 25 and 60 km/h; note if your bike wants different ones.
- [ ] Stop at a traffic light for 5 seconds: the preset does not change.
- [ ] Before the first fix, the strength you saved is used. If the fix is lost later
      (tunnel, location switched off) the last Auto choice stays in force; it does not jump.
- [ ] Pick Light, Medium, Strong or Off by hand (settings row or the call screen's noise
      button): Auto switches itself off and stays off.
- [ ] Auto is not saved as a changed strength: after ending the call the strength shown in
      Settings is the one you picked, not whatever Auto last chose.

## Phase 7 (rider controls and safety)

None of this has run on a phone. CI proves it compiles and the pure logic passes (SOS
trigger, send schedule, dedupe, button mapping, duck policy, link tones, alert patterns).
Note the phone model and Android version next to each item.

### Mute and deafen
- [ ] Mute button on the call screen: buddy stops hearing you, your mic bar goes flat, the
      button shows "UNMUTE". Unmute: buddy hears you again within a second.
- [ ] Mute while you are talking: the floor is released at once (buddy's dial leaves amber).
- [ ] Deafen: you hear no voice from the intercom, the mic is off, and the buddy sees
      "deafened". An incoming SOS siren still sounds.
      Undeafen restores your previous mute state (muted-before stays muted).
- [ ] Notification actions Mute/Unmute and Deafen/Undeafen change the same state as the
      screen, with the screen on and off. Rotating or reopening the app keeps the state.
- [ ] Buddy's phone shows "<name> is muted / deafened" within a few seconds.
- [ ] Ending the call and starting another resets both to off.

### Headset and volume keys
- [ ] Default mapping: double press = push to talk, triple press = mute, long press = end
      call. Triple press no longer ends the call.
- [ ] Change a mapping in Settings (Remote buttons): the new action fires on the next press
      without restarting the call.
- [ ] Bluetooth media-key device other than your headset (handlebar remote, ring remote):
      note which key codes arrive and whether the mapping works.
- [ ] Volume keys opt-in OFF: volume buttons change the volume as normal during a call.
- [ ] Volume keys opt-in ON: volume up = push to talk, volume down = mute, volume no longer
      changes during the call, and goes back to normal after the call ends.
- [ ] Headset play/pause with the opt-in ON is taken by the intercom for the whole call;
      your music app's play/pause from the headset no longer works until the call ends.

### Music ducking
- [ ] Play music in another app. When the buddy speaks it lowers (not pauses) and returns
      about 0.7 s after they stop. Your own speech does not lower it by default.
- [ ] "Lower music when I talk too" ON: your speech lowers it as well.
- [ ] Deafened: music is not lowered at all.
- [ ] Apps that ignore ducking requests (note which): music stays loud, nothing breaks.
- [ ] Group ride: ducking follows whoever holds the floor.

### SOS
- [ ] One tap arms the button ("TAP AGAIN TO SEND"); doing nothing disarms it after 5 s;
      a second tap within 5 s sends. Pocket or glove taps cannot send on one touch.
- [ ] Holding for 3 s sends; releasing early cancels, and the fill bar resets.
- [ ] Sender sees "SOS sent with your last known position" (location sharing ON) or
      "...without a position" (OFF), and the alert is repeated twice over about 5 s.
- [ ] Receiver: full-screen red banner with name, distance and compass direction (if both
      phones share location), coordinates, position age and time; siren plays and repeats
      about every 9 s up to 6 times; Dismiss stops it.
- [ ] Receiver deafened or muted: siren and banner still appear.
- [ ] Receiver's phone locked, screen off: notification appears with the full-screen
      intent and wakes the screen. Test on Android 14+, where the "full-screen notifications"
      permission can be revoked per app; note what the phone does.
- [ ] SOS with the link down: shows "waiting for the link", sends when the link returns,
      and reports "not sent" after two minutes without one.
- [ ] Buddy on a v1 build: the SOS button is disabled with an explanation.
- [ ] Group ride: SOS reaches every rider, including muted ones, once each (no repeats
      from the mesh).
- [ ] No coordinates appear in the notification text, and nothing leaves the phones.

### Link loss and reconnect
- [ ] Walk the phones out of range: falling tone on loss, call keeps retrying (Bluetooth:
      1, 2, 4, 8 s; Wi-Fi Direct: 2, 4, 8, 15 s), rising tone when the link is back.
- [ ] A flaky link that drops and returns repeatedly does not play a tone storm (at most
      one "lost" tone per 10 s).
- [ ] "Link tones" OFF: no tones, reconnect still happens.
- [ ] Start screen shows "Reconnect to <buddy>" after a call; as the joiner it needs the
      buddy still paired, as the host it starts hosting again. Hidden for group rides.

## Phase 8 (onboarding, languages, accessibility, diagnostics, ride summary)

The Hindi text was written without a native review. Have a Hindi speaker read every screen
before release; the English text is the source of truth.

### First-run walkthrough
- [ ] Fresh install: the walkthrough opens before the start screen. Progress bar and
      "Step n of m" are correct; Skip setup leaves at once and is not shown again.
- [ ] Each permission page shows its reason, the button opens the system dialog, and the
      state flips to "Allowed" on return. Check on Android 10, 11, 12/12L, 13 and 14+
      (Bluetooth page asks for location on 10 and 11; Notifications page only on 13+;
      Location page only when "share position" is on).
- [ ] Deny a permission twice: "Open app settings" appears and works.
- [ ] Bluetooth off: the page offers "Turn Bluetooth on" and it works.
- [ ] Battery page: the exemption button works, then reads "Done". On Xiaomi, OPPO/realme,
      vivo, OnePlus, Samsung and Huawei the vendor steps are the right ones for that phone
      and "Open settings" lands on the vendor screen (or does nothing harmful).
- [ ] Settings > Run setup again reopens it; Back from it returns to the start screen.

### Test call
- [ ] Wired headset, Bluetooth headset in call mode, A2DP-only headset, phone only: the
      "Audio route" lines name the right device and the advice sentence is right (headset
      mic not in use, loudspeaker, nothing to listen through).
- [ ] Meter follows your voice during the 4 s; the recording plays back through the headset.
- [ ] Very quiet and very loud input give the "could not hear you" and "may distort" notes.
- [ ] Another app holding the microphone gives the "could not be opened" message.
- [ ] Button is disabled during a call and without the microphone permission.
- [ ] Leaving the page during playback stops it.

### Language
- [ ] Settings > Language: Hindi, English, "Same as the phone". The app rebuilds in place
      and lands on Settings again.
- [ ] With the phone in Hindi and the app on English (and the reverse) nothing mixes:
      notification title and actions, notification channel names in Android settings, the
      SOS banner, error messages, transport status lines, ride summary.
- [ ] Android 13+: Settings > System > Languages > App languages lists Hindi and English.
- [ ] Devanagari renders without clipped or overlapping text on small and large phones.
- [ ] Ride-code words stay English on both phones whatever the app language.

### Accessibility
- [ ] TalkBack: every icon button has a spoken name; switches and radio rows read as one item;
      the mic level, link-quality graph and QR code have descriptions.
- [ ] Android font size at maximum plus display size at maximum: nothing is cut off, buttons
      grow, screens scroll.
- [ ] High contrast in direct sunlight with a visor: readable, status colours still distinct.
- [ ] Large buttons with winter gloves: Talk, Mute, Deafen, SOS and Start can be hit first time.
- [ ] Every tappable control is at least 48 dp (developer options > Show layout bounds).

### Share log
- [ ] Settings > Share log opens the share sheet with a .txt file.
- [ ] Open it: no coordinates, no ride code or code words, no rider or buddy names, no full
      Bluetooth addresses, no passphrase. Header says what was removed.
- [ ] Share to Gmail/Drive/Files works (the file provider grants read access).
- [ ] Nothing is sent without choosing an app; airplane mode makes no difference.

### Ride summary
- [ ] A call of at least 10 s that connected shows the summary when it ends: duration,
      connected %, drops, delay, loss, graph, radio switches. Shorter or never-connected
      calls show nothing.
- [ ] Walk out of range and back: the drop appears as a red dot and counts as a link drop.
- [ ] Force a Bluetooth to Wi-Fi Direct switch: it is listed with the time.
- [ ] Group ride: rider count is right; no per-rider data or positions shown.
- [ ] Back and Done both clear the summary; reopening the app does not show it again.

### Dependencies
- [ ] Smoke-test a full call, group call and SOS after the Compose BOM, core-ktx,
      activity-compose and DataStore bumps; release build with R8 and resource shrinking
      still installs and runs (Hindi strings survive shrinking).

## Phase 9: safe in-app updates

Needs a real signed release on GitHub Releases for `Vedansh-rar/intercom`; none exists yet, so
none of this has run end to end. Build two signed APKs with the same key, the second with a
higher `versionCode` and a tag such as `v2.0.0`, and install the first.

### Happy path
- [ ] Settings > Updates says checks are manual or opt-in and names GitHub as the only host.
- [ ] Check for updates with no release published: "No release has been published yet."
- [ ] With a release: it offers the right version and size; the notes snippet reads sensibly.
- [ ] Download shows progress and finishes; Install opens the system installer.
- [ ] First time: Android's "install unknown apps" screen opens for this app; after allowing it
      and returning, Install goes to the installer; the update installs over the old build and
      keeps settings, ride code and onboarding state.
- [ ] Hindi: every message on this screen is in Hindi and fits at largest font size.

### Refusals (each should delete the file and say why in words)
- [ ] Release APK signed with a different key: refused as "different key" before the installer.
- [ ] Edit the `.sha256` asset to a wrong hash: "does not match", nothing installed.
- [ ] Release with no `.sha256` and no hash in the notes: not offered, says no checksum.
- [ ] Two different hashes in the notes: not offered.
- [ ] Tag older than or equal to the installed version, and a tag such as `nightly`: never offered.
- [ ] `v2.0.0-rc.1` marked pre-release is not returned by "latest"; a full `v1.9.0` is not offered over 2.0.0.
- [ ] Same `versionCode` as installed: refused as not newer.
- [ ] APK attached under a different package name: refused.

### Download behaviour
- [ ] Cancel mid-download: returns to "available", `cache/updates` holds no `.part` file.
- [ ] Airplane mode mid-download: clear offline message, no partial file kept, retry works.
- [ ] Low storage: "not enough space", no partial file.
- [ ] A large APK downloads without memory growth (watch Android Studio profiler).
- [ ] Rotate the phone and switch language during a download: the state survives.
- [ ] Rate-limited (repeat checks quickly, or use a tethered network that is rate limited): friendly message.

### Calls and opt-in
- [ ] During a call, Download and Install are disabled with the explanation; the call is unaffected.
- [ ] Fresh install: no network traffic at launch (check with a packet capture or `adb shell dumpsys netstats`).
- [ ] Daily check switched on: one check per day at app launch, never during a call, never downloads.
- [ ] Switch it off: no more checks. Clock set back: it checks again (counts as due), then waits a day.
- [ ] Cleartext traffic is blocked (network security config): a check through a proxy serving plain http fails.

### Known gaps to confirm
- [ ] Signing-key rotation with a lineage is not handled: the app refuses such an update. Decide if that is acceptable.
- [ ] Sideloaded updates are not shown in Play Protect prompts consistently across brands; note the prompts on Xiaomi, Samsung and Oppo.

## Phase 10: internet rides through a relay

Needs a deployed relay (`server/README.md`) reachable at a `wss://` address, and two or three
phones on mobile data, ideally on different carriers and far apart.

### Setup and refusals
- [ ] Settings > Internet ride: typing `ws://...`, a path, or an address with spaces shows the red message; a valid `wss://host` clears it.
- [ ] Internet on with no address: starting a group ride says to set one. Internet on with a 6-digit code: refused, asks for a word code.
- [ ] Hindi: all of the Internet ride card and the messages are in Hindi and fit at large font size.
- [ ] With internet off (default) a group ride behaves exactly as before and makes no network request (check with a packet capture or `dumpsys netstats`).

### Two and three riders
- [ ] Two phones, same word code, internet on, Wi-Fi off, different cities: connect within a few seconds; both hear each other; floor and mute work.
- [ ] Three or more phones: everyone hears the one who has the floor; priority takes the floor from a lower one.
- [ ] Wrong code on one phone: it never connects, the others are unaffected, the wrong-code message shows.
- [ ] A rider with the right code joins late: gets in and is heard.
- [ ] Latency: talk and listen; note how it feels on 4G and on 5G. Anything over about 400 ms makes turn-taking awkward; write down what you see.
- [ ] SOS sent from one phone reaches the others.

### Mobile data in real life
- [ ] Ride through a tunnel or switch airplane mode on for 10 s and off: the status says it lost the connection, then reconnects and the pair re-handshakes by itself; audio resumes with no restart.
- [ ] Switch from Wi-Fi to mobile data (and back) during a ride: reconnects.
- [ ] Lock the phone and put it in a pocket for 30 minutes: the ride is still connected (foreground service, battery optimisation exempt).
- [ ] Measure data use over an hour with one rider mostly talking: expect roughly 16 MB per listening rider per hour of talking.
- [ ] Battery drain per hour compared with a Bluetooth ride.

### Relay and privacy
- [ ] Relay unreachable or wrong address: "Lost the connection - reconnecting" with growing delays, never a crash; the ride resumes when the relay is back.
- [ ] 13th rider: refused with "The room is full".
- [ ] Look at the relay's logs: no packet contents, ride codes or names (it logs one startup line only).
- [ ] Capture the traffic to the relay: only TLS to your host; no other host is contacted.
- [ ] Positions: with Share location on, an internet ride shows no distances and sends nothing.
- [ ] Known gaps to decide on: there is no latency measurement or adaptive bitrate; no relay discovery; a relay operator can test short ride codes offline.
