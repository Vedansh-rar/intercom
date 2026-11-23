package com.intercom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intercom.app.audio.MicSource
import com.intercom.app.audio.NoiseEngine
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.audio.WindCutFilter
import com.intercom.app.floor.VadSensitivity
import com.intercom.app.location.DistanceFormat
import com.intercom.app.location.DistanceUnit
import com.intercom.app.media.ButtonInput
import com.intercom.app.media.ButtonMapping
import com.intercom.app.util.Settings as RiderSettings
import com.intercom.app.util.TalkMode
import com.intercom.app.util.TransportPreference
import kotlin.math.roundToInt

/**
 * Everything the rider can change. The wording matters here: each option says what it
 * costs, because most of these are trade-offs rather than preferences.
 */
@Composable
fun SettingsScreen(
    settings: RiderSettings,
    lastKeyCode: Int,
    platformEffects: String = "",
    wifiDirectReady: Boolean = true,
    wifiDirectProblem: String = "",
    onChange: ((RiderSettings) -> RiderSettings) -> Unit,
    onBack: () -> Unit,
    onOem: () -> Unit,
    onHfp: () -> Unit = {},
    /** Switching sharing on may need the location permission first, so the caller owns it. */
    onShareLocation: (Boolean) -> Unit = { on -> onChange { it.copy(shareLocation = on) } },
    /** The rider was asked for location and said no (or allowed approximate only). */
    locationDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        // Hidden: a long-press on the title flips the legacy audio path, an escape hatch
        // for phones where Oboe misbehaves. There is deliberately no visible switch.
        Box(
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onChange { it.copy(legacyAudioPath = !it.legacyAudioPath) } },
                )
            },
        ) {
            ScreenHeader(title = "Settings", onBack = onBack)
        }
        if (settings.legacyAudioPath) {
            Text(
                "Legacy audio path is on (AudioRecord/AudioTrack instead of Oboe). " +
                    "Long-press the title to switch back. Applies from the next call.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionCard(title = "How you talk") {
            Text(
                "An open call is the point of this app: the channel is always up and " +
                    "speaking is all it takes. Push-to-talk is there for helmets too noisy " +
                    "for a detector, or riders who would rather hold a button.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            TalkMode.entries.forEach { mode ->
                ChoiceRow(
                    selected = settings.talkMode == mode,
                    title = mode.label,
                    subtitle = when (mode) {
                        TalkMode.Open -> "Voice-activated. First speaker takes the channel; " +
                            "everyone else is muted until they stop."
                        TalkMode.PushToTalk -> "The detector never opens the channel. Only " +
                            "the on-screen button, the notification or the headset button do."
                    },
                    onClick = { onChange { it.copy(talkMode = mode) } },
                )
            }
        }

        SectionCard(title = "Microphone") {
            Text(
                "The headset mic is the one trap in this design: opening it drops the " +
                    "headset off A2DP and music turns mono for the rest of the ride.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            MicSource.entries.forEach { src ->
                ChoiceRow(
                    selected = settings.micSource == src,
                    title = micTitle(src),
                    subtitle = micSubtitle(src),
                    onClick = { onChange { it.copy(micSource = src) } },
                )
            }
        }

        SectionCard(title = "Noise cancellation") {
            Text(
                "This cleans the voice you send, so your buddy hears you instead of your " +
                    "engine. It cannot cancel noise at your own ear the way an aviation " +
                    "headset does \u2014 that needs a microphone inside the earcup and a " +
                    "sub-millisecond loop, which is a property of the headset, not the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text("Noise reduction engine", style = MaterialTheme.typography.bodyLarge)
            ChoiceRow(
                selected = settings.noiseEngine == NoiseEngine.Standard,
                title = "Standard (recommended)",
                subtitle = "Light on the battery and the processor, with a strength you choose. " +
                    "Good for steady noise such as engine hum and road roar.",
                onClick = { onChange { it.copy(noiseEngine = NoiseEngine.Standard) } },
            )
            ChoiceRow(
                selected = settings.noiseEngine == NoiseEngine.Ai,
                title = "AI noise reduction",
                subtitle = "A small neural network (RNNoise) that copes better with gusty, " +
                    "uneven noise. It has no strength setting, uses noticeably more of the " +
                    "processor and battery, and adds about 11 ms of delay. If your phone cannot " +
                    "keep up, the app switches back to Standard by itself.",
                onClick = { onChange { it.copy(noiseEngine = NoiseEngine.Ai) } },
            )
            if (settings.noiseEngine == NoiseEngine.Ai) {
                ToggleRow(
                    checked = settings.noiseSuppression != NoiseSuppression.Off,
                    title = "AI noise reduction on",
                    subtitle = "Switch off to send your voice with only the wind low-cut and the " +
                        "noise gate.",
                    onChange = { on ->
                        onChange {
                            it.copy(
                                noiseSuppression = if (!on) NoiseSuppression.Off
                                else if (it.noiseSuppression == NoiseSuppression.Off) NoiseSuppression.Light
                                else it.noiseSuppression,
                            )
                        }
                    },
                )
            } else {
                ToggleRow(
                    checked = settings.autoNoise,
                    title = "Auto: choose the strength from my speed",
                    subtitle = "Light in town, Medium on faster roads, Strong on the highway, " +
                        "switching only after the speed has held for about 10 seconds. Needs " +
                        "\"Share my location\" below; until there is a GPS fix it uses " +
                        "the strength you picked, and it keeps its last choice if the fix is " +
                        "lost. Picking a strength yourself turns Auto off.",
                    onChange = { on ->
                        onChange {
                            it.copy(
                                autoNoise = on,
                                noiseSuppression =
                                    if (on && it.noiseSuppression == NoiseSuppression.Off) NoiseSuppression.Light
                                    else it.noiseSuppression,
                            )
                        }
                    },
                )
                NoiseSuppression.entries.forEach { ns ->
                    ChoiceRow(
                        selected = settings.noiseSuppression == ns,
                        title = ns.label,
                        subtitle = noiseSubtitle(ns),
                        onClick = { onChange { it.copy(noiseSuppression = ns, autoNoise = false) } },
                    )
                }
            }
            if (platformEffects.isNotEmpty()) {
                Text(
                    "This phone also offers: $platformEffects. Those run in hardware and " +
                        "are used automatically when noise cancellation is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
            }
        }

        SectionCard(title = "Wind low-cut") {
            Text(
                "Removes the low thump of wind and engine from the voice you send. Raise it " +
                    "if the highway still booms in your buddy's ears; lower it if your voice " +
                    "sounds thin.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(
                if (settings.windCutHz == 0) "Off" else "${settings.windCutHz} Hz",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = if (settings.windCutHz == 0) 70f else settings.windCutHz.toFloat(),
                onValueChange = { v ->
                    val hz = (v / WindCutFilter.STEP_HZ).roundToInt() * WindCutFilter.STEP_HZ
                    onChange { it.copy(windCutHz = if (hz < WindCutFilter.MIN_HZ) 0 else hz) }
                },
                valueRange = 70f..WindCutFilter.MAX_HZ.toFloat(),
                steps = (WindCutFilter.MAX_HZ - 70) / WindCutFilter.STEP_HZ - 1,
                colors = sliderColors(),
            )
            if (settings.windCutHz != 0) {
                ToggleRow(
                    checked = settings.windCutSteep,
                    title = "Steeper slope",
                    subtitle = "Cuts twice as sharply below the frequency. More wind removed, " +
                        "slightly more change to the sound near the cutoff.",
                    onChange = { v -> onChange { it.copy(windCutSteep = v) } },
                )
            }
            if (settings.windCutHz != WindCutFilter.DEFAULT_HZ || settings.windCutSteep) {
                SecondaryButton(
                    text = "Reset to ${WindCutFilter.DEFAULT_HZ} Hz",
                    onClick = {
                        onChange { it.copy(windCutHz = WindCutFilter.DEFAULT_HZ, windCutSteep = false) }
                    },
                )
            }
        }

        SectionCard(title = "Even out my voice") {
            ToggleRow(
                checked = settings.agc,
                title = "Even out my voice level",
                subtitle = "Slowly raises your voice when you talk quietly or the mic has slipped, " +
                    "lowers it when you shout, and stops it ever clipping. It changes over a " +
                    "few seconds, never between words, and it leaves silence alone. Off by default.",
                onChange = { v -> onChange { it.copy(agc = v) } },
            )
        }

        SectionCard(title = "Voice detection") {
            Text(
                "How eagerly the channel opens when you speak. Lower sensitivity at " +
                    "highway speed, higher with a boom mic.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            VadSensitivity.entries.forEach { v ->
                ChoiceRow(
                    selected = settings.vadSensitivity == v,
                    title = v.name,
                    subtitle = "${v.thresholdDb.toInt()} dB over the noise floor, " +
                        "${v.attackFrames * 20} ms to open",
                    onClick = { onChange { it.copy(vadSensitivity = v) } },
                )
            }
        }

        SectionCard(title = "Transport") {
            TransportPreference.entries.forEach { t ->
                ChoiceRow(
                    selected = settings.transport == t,
                    title = t.name,
                    subtitle = when (t) {
                        TransportPreference.Auto -> "Bluetooth, moving to Wi-Fi Direct if it degrades"
                        TransportPreference.Bluetooth -> "Bluetooth RFCOMM only, 10 to 30 m"
                        TransportPreference.Range -> "Start on Wi-Fi Direct: 50-100 m, more battery"
                    },

                    onClick = { onChange { it.copy(transport = t) } },
                )
            }
            Text(
                if (wifiDirectReady) "Wi-Fi Direct is ready on this phone. Auto will move " +
                    "the call onto it if Bluetooth starts losing frames."
                else "Wi-Fi Direct is not usable right now: $wifiDirectProblem. Auto will " +
                    "stay on Bluetooth until that is fixed.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (wifiDirectReady) IntercomColors.dim else Brand.Buddy,
            )
        }

        SectionCard(title = "Voice level") {
            Text(
                "${settings.voiceLevelDb} dB",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.voiceLevelDb.toFloat(),
                onValueChange = { onChange { s -> s.copy(voiceLevelDb = it.toInt()) } },
                valueRange = -12f..12f,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = Brand.Accent,
                    activeTrackColor = Brand.Accent,
                    inactiveTrackColor = Brand.SurfaceElevated,
                    activeTickColor = Brand.AccentDeep,
                    inactiveTickColor = Brand.Border,
                ),
            )
            Text(
                "Music stays on the phone's media volume. The headset volume rocker " +
                    "moves both together, because the voice plays as media.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = "Music") {
            ToggleRow(
                checked = settings.takeAudioFocus,
                title = "Duck music while talking",
                subtitle = "Off: the intercom takes no audio focus at all, music keeps " +
                    "playing at full volume and the voice is lifted 6 dB instead. Use this " +
                    "if your music app pauses rather than ducking.",
                onChange = { v -> onChange { it.copy(takeAudioFocus = v) } },
            )
        }

        SectionCard(title = "Mute beep") {
            ToggleRow(
                checked = settings.beepWhenMuted,
                title = "Beep when you talk over your buddy",
                subtitle = "A short local tone, never sent to the other rider, at most " +
                    "one every two seconds.",
                onChange = { v -> onChange { it.copy(beepWhenMuted = v) } },
            )
        }

        SectionCard(title = "Headset button") {
            ToggleRow(
                checked = settings.headsetButtonEnabled,
                title = "Use the headset button",
                subtitle = "Double press toggles push-to-talk, triple or long press ends " +
                    "the call. Single press is left to your music app.",
                onChange = { v -> onChange { it.copy(headsetButtonEnabled = v) } },
            )
            Text(
                if (lastKeyCode == 0)
                    "Calibration: start a call, then press your headset button here to " +
                        "see which key code it sends."
                else "Last key code received: $lastKeyCode",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(
                "Headsets differ, and some map their main button to the voice assistant, " +
                    "which the app cannot change.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = "Remote buttons") {
            Text(
                "Tap a row to change what it does. Press-and-hold gestures depend on your " +
                    "headset, so check them with the key code line above.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            ButtonInput.entries.filter {
                settings.volumeKeys || (it != ButtonInput.VolumeUp && it != ButtonInput.VolumeDown)
            }.forEach { input ->
                val action = settings.buttons.actionFor(input)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = {
                            onChange { it.copy(buttons = it.buttons.with(input, action.next())) }
                        })
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(input.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Accent,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
            ToggleRow(
                checked = settings.volumeKeys,
                title = "Use the volume buttons during a call",
                subtitle = "While a call is running the volume buttons stop changing the " +
                    "volume and do what is set above instead, and the headset play/pause " +
                    "button is taken by the intercom too. Off by default.",
                onChange = { v -> onChange { it.copy(volumeKeys = v) } },
            )
            SecondaryButton(
                text = "Reset buttons to defaults",
                onClick = { onChange { it.copy(buttons = ButtonMapping()) } },
                height = 46.dp,
            )
        }

        SectionCard(title = "Music and tones") {
            ToggleRow(
                checked = settings.duckWhenITalk,
                title = "Lower music when I talk too",
                subtitle = "Music always lowers while your buddy speaks. Turn this on to lower " +
                    "it while you speak as well.",
                onChange = { v -> onChange { it.copy(duckWhenITalk = v) } },
            )
            ToggleRow(
                checked = settings.linkTones,
                title = "Tones when the link drops and returns",
                subtitle = "A short falling tone when the connection is lost, a rising one " +
                    "when it comes back. The app keeps trying to reconnect either way.",
                onChange = { v -> onChange { it.copy(linkTones = v) } },
            )
        }

        SectionCard(title = "Location") {
            ToggleRow(
                checked = settings.shareLocation,
                title = "Share my location with riders",
                subtitle = "Shows how far away your buddy or group is, and lets them see how " +
                    "far you are. While this is on, your phone reads its GPS and sends your " +
                    "position straight to the riders in your call, encrypted with your ride " +
                    "code. It goes phone to phone only: there is no server and no internet, " +
                    "nothing is stored, and it stops when the call ends. You see distances " +
                    "only while you share your own. It costs some battery. Off by default.",
                onChange = onShareLocation,
            )
            if (locationDenied) {
                Text(
                    "Precise location was not allowed, so nothing is shared. Allow \"Precise\" " +
                        "location for this app in the phone's settings, then switch this on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.Buddy,
                )
                SecondaryButton("Open app settings", onOpenAppSettings)
            }
            Text("Show distances in", style = MaterialTheme.typography.bodyLarge)
            DistanceUnit.entries.forEach { u ->
                ChoiceRow(
                    selected = settings.distanceUnit == u,
                    title = when (u) {
                        DistanceUnit.Kilometres -> "Kilometres (m / km)"
                        DistanceUnit.Miles -> "Miles (ft / mi)"
                    },
                    subtitle = "",
                    onClick = { onChange { it.copy(distanceUnit = u) } },
                )
            }
            ToggleRow(
                checked = settings.fallingBehindAlert,
                title = "Warn me when a rider falls behind",
                subtitle = "A short beep on your own phone (never sent to anyone) when a " +
                    "rider stays farther away than the distance below for the time below, " +
                    "then again every 30 seconds while they are still out. Needs sharing on.",
                onChange = { v -> onChange { it.copy(fallingBehindAlert = v) } },
            )
            if (settings.fallingBehindAlert) {
                val far = DistanceFormat.format(
                    settings.fallingBehindMeters.toDouble(), settings.distanceUnit,
                ).text
                Text("Farther than $far", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.fallingBehindMeters.toFloat(),
                    onValueChange = { v ->
                        onChange { s -> s.copy(fallingBehindMeters = (v / 100f).roundToInt().coerceAtLeast(1) * 100) }
                    },
                    valueRange = 100f..2000f,
                    steps = 18,
                    colors = sliderColors(),
                )
                Text(
                    "for ${settings.fallingBehindSeconds} seconds",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = settings.fallingBehindSeconds.toFloat(),
                    onValueChange = { v ->
                        onChange { s -> s.copy(fallingBehindSeconds = (v / 5f).roundToInt().coerceAtLeast(1) * 5) }
                    },
                    valueRange = 5f..60f,
                    steps = 10,
                    colors = sliderColors(),
                )
            }
        }

        SectionCard(title = "Ride security") {
            Text(
                "Calls are encrypted with your ride code. A buddy who has the wrong code, " +
                    "or whose app cannot encrypt, is refused.",
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            ToggleRow(
                checked = settings.allowLegacyBuddy,
                title = "Allow an unencrypted buddy",
                subtitle = "Lets a buddy on an older version of this app connect without " +
                    "encryption or a ride code check. Anyone nearby could then listen in, " +
                    "and the call screen shows a warning for as long as it lasts.",
                onChange = { v -> onChange { it.copy(allowLegacyBuddy = v) } },
            )
        }

        SectionCard(title = "Keeping the call alive") {
            SecondaryButton("Background and battery steps", onOem)
        }

        SectionCard(title = "Intercom hardware") {
            SecondaryButton("HFP intercom bridge (C30 / universal)", onHfp)
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Brand.Accent,
    activeTrackColor = Brand.Accent,
    inactiveTrackColor = Brand.SurfaceElevated,
    activeTickColor = Brand.AccentDeep,
    inactiveTickColor = Brand.Border,
)

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = Brand.Accent,
                unselectedColor = Brand.Idle,
                disabledUnselectedColor = Brand.Border,
            ),
        )
        Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else IntercomColors.dim,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Brand.TextPrimary,
                checkedTrackColor = Brand.Accent,
                uncheckedThumbColor = Brand.TextDim,
                uncheckedTrackColor = Brand.SurfaceElevated,
                uncheckedBorderColor = Brand.Border,
            ),
        )
    }
}

private fun micTitle(src: MicSource): String = when (src) {
    MicSource.Wired -> "Wired boom mic (best)"
    MicSource.Phone -> "Phone microphone"
    MicSource.LeAudio -> "LE Audio headset"
    MicSource.HeadsetHfp -> "Headset mic (music quality drops)"
}

private fun micSubtitle(src: MicSource): String = when (src) {
    MicSource.Wired -> "USB-C or 3.5 mm, mounted in the helmet. Best wind rejection, " +
        "and the headset stays on A2DP so music stays stereo."
    MicSource.Phone -> "Keeps the headset on A2DP. Usable at town speeds; wind beats it " +
        "on the highway."
    MicSource.LeAudio -> "Android 13+ with an LE Audio headset: mic and high-quality " +
        "output at once. Few helmet headsets support it yet."
    MicSource.HeadsetHfp -> "Convenient, but Android suspends A2DP while the headset mic " +
        "is open, so music turns mono and muffled for the whole call."
}

private fun noiseSubtitle(ns: NoiseSuppression): String = when (ns) {
    NoiseSuppression.Off -> "Only the wind low-cut and the noise gate. Most natural voice, " +
        "but your buddy hears the bike."
    NoiseSuppression.Light -> "Town riding. Takes out the steady hiss and rumble without " +
        "touching the voice."
    NoiseSuppression.Medium -> "Mixed roads. Stronger than Light, still natural; a good " +
        "default when speeds vary."
    NoiseSuppression.Strong -> "Highway. Removes engine and wind roar; the voice sounds " +
        "slightly processed in exchange."
}
