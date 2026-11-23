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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.selection.toggleable
import com.intercom.app.util.AppLanguage
import com.intercom.app.R
import androidx.compose.ui.res.stringResource

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
    onLanguage: (AppLanguage) -> Unit = {},
    onShareLog: () -> Unit = {},
    shareLogBusy: Boolean = false,
    onRunSetup: () -> Unit = {},
    updateState: com.intercom.app.update.UpdateState = com.intercom.app.update.UpdateState.Idle,
    appVersion: String = "",
    callActive: Boolean = false,
    updateActions: UpdateActions = UpdateActions(),
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
            ScreenHeader(title = stringResource(R.string.common_settings), onBack = onBack)
        }
        if (settings.legacyAudioPath) {
            Text(
                stringResource(R.string.set_legacy_on),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionCard(title = stringResource(R.string.set_language)) {
            Text(
                stringResource(R.string.set_language_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            AppLanguage.entries.forEach { lang ->
                ChoiceRow(
                    selected = settings.language == lang,
                    title = stringResource(lang.titleRes()),
                    subtitle = if (lang == AppLanguage.System) stringResource(R.string.set_language_system_sub) else "",
                    onClick = { onLanguage(lang) },
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_comfort)) {
            ToggleRow(
                checked = settings.highContrast,
                title = stringResource(R.string.set_high_contrast),
                subtitle = stringResource(R.string.set_high_contrast_sub),
                onChange = { v -> onChange { it.copy(highContrast = v) } },
            )
            ToggleRow(
                checked = settings.largeButtons,
                title = stringResource(R.string.set_large_buttons),
                subtitle = stringResource(R.string.set_large_buttons_sub),
                onChange = { v -> onChange { it.copy(largeButtons = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_how_talk)) {
            Text(
                stringResource(R.string.set_talk_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            TalkMode.entries.forEach { mode ->
                ChoiceRow(
                    selected = settings.talkMode == mode,
                    title = stringResource(mode.labelRes()),
                    subtitle = when (mode) {
                        TalkMode.Open -> stringResource(R.string.set_talk_open_sub)
                        TalkMode.PushToTalk -> stringResource(R.string.set_talk_ptt_sub)
                    },
                    onClick = { onChange { it.copy(talkMode = mode) } },
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_microphone)) {
            Text(
                stringResource(R.string.set_mic_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            MicSource.entries.forEach { src ->
                ChoiceRow(
                    selected = settings.micSource == src,
                    title = stringResource(src.titleRes()),
                    subtitle = stringResource(src.subtitleRes()),
                    onClick = { onChange { it.copy(micSource = src) } },
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_noise_cancel)) {
            Text(
                stringResource(R.string.set_noise_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(stringResource(R.string.set_noise_engine), style = MaterialTheme.typography.bodyLarge)
            ChoiceRow(
                selected = settings.noiseEngine == NoiseEngine.Standard,
                title = stringResource(R.string.set_engine_standard),
                subtitle = stringResource(R.string.set_engine_standard_sub),
                onClick = { onChange { it.copy(noiseEngine = NoiseEngine.Standard) } },
            )
            ChoiceRow(
                selected = settings.noiseEngine == NoiseEngine.Ai,
                title = stringResource(R.string.set_engine_ai),
                subtitle = stringResource(R.string.set_engine_ai_sub),
                onClick = { onChange { it.copy(noiseEngine = NoiseEngine.Ai) } },
            )
            if (settings.noiseEngine == NoiseEngine.Ai) {
                ToggleRow(
                    checked = settings.noiseSuppression != NoiseSuppression.Off,
                    title = stringResource(R.string.set_ai_on),
                    subtitle = stringResource(R.string.set_ai_on_sub),
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
                    title = stringResource(R.string.set_auto_noise),
                    subtitle = stringResource(R.string.set_auto_noise_sub),
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
                        title = stringResource(ns.labelRes()),
                        subtitle = stringResource(ns.subtitleRes()),
                        onClick = { onChange { it.copy(noiseSuppression = ns, autoNoise = false) } },
                    )
                }
            }
            if (platformEffects.isNotEmpty()) {
                Text(
                    stringResource(R.string.set_platform_effects, platformEffects),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_wind_cut)) {
            Text(
                stringResource(R.string.set_wind_cut_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(
                if (settings.windCutHz == 0) stringResource(R.string.common_off) else stringResource(R.string.set_hz, settings.windCutHz),
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
                    title = stringResource(R.string.set_steeper),
                    subtitle = stringResource(R.string.set_steeper_sub),
                    onChange = { v -> onChange { it.copy(windCutSteep = v) } },
                )
            }
            if (settings.windCutHz != WindCutFilter.DEFAULT_HZ || settings.windCutSteep) {
                SecondaryButton(
                    text = stringResource(R.string.set_reset_hz, WindCutFilter.DEFAULT_HZ),
                    onClick = {
                        onChange { it.copy(windCutHz = WindCutFilter.DEFAULT_HZ, windCutSteep = false) }
                    },
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_even_voice)) {
            ToggleRow(
                checked = settings.agc,
                title = stringResource(R.string.set_even_voice_level),
                subtitle = stringResource(R.string.set_even_voice_sub),
                onChange = { v -> onChange { it.copy(agc = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_voice_detect)) {
            Text(
                stringResource(R.string.set_voice_detect_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            VadSensitivity.entries.forEach { v ->
                ChoiceRow(
                    selected = settings.vadSensitivity == v,
                    title = stringResource(v.labelRes()),
                    subtitle = stringResource(R.string.set_vad_row, v.thresholdDb.toInt(), v.attackFrames * 20),
                    onClick = { onChange { it.copy(vadSensitivity = v) } },
                )
            }
        }

        SectionCard(title = stringResource(R.string.set_transport)) {
            TransportPreference.entries.forEach { t ->
                ChoiceRow(
                    selected = settings.transport == t,
                    title = stringResource(t.labelRes()),
                    subtitle = when (t) {
                        TransportPreference.Auto -> stringResource(R.string.set_transport_auto_sub)
                        TransportPreference.Bluetooth -> stringResource(R.string.set_transport_bt_sub)
                        TransportPreference.Range -> stringResource(R.string.set_transport_range_sub)
                    },

                    onClick = { onChange { it.copy(transport = t) } },
                )
            }
            Text(
                if (wifiDirectReady) stringResource(R.string.set_wifi_ready)
                else stringResource(R.string.set_wifi_not_ready, wifiDirectProblem),
                style = MaterialTheme.typography.bodyMedium,
                color = if (wifiDirectReady) IntercomColors.dim else Brand.Buddy,
            )
        }

        SectionCard(title = stringResource(R.string.set_voice_level)) {
            Text(
                stringResource(R.string.set_db, settings.voiceLevelDb),
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
                stringResource(R.string.set_voice_level_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = stringResource(R.string.common_music)) {
            ToggleRow(
                checked = settings.takeAudioFocus,
                title = stringResource(R.string.set_duck),
                subtitle = stringResource(R.string.set_duck_sub),
                onChange = { v -> onChange { it.copy(takeAudioFocus = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_mute_beep)) {
            ToggleRow(
                checked = settings.beepWhenMuted,
                title = stringResource(R.string.set_mute_beep_title),
                subtitle = stringResource(R.string.set_mute_beep_sub),
                onChange = { v -> onChange { it.copy(beepWhenMuted = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_headset_button)) {
            ToggleRow(
                checked = settings.headsetButtonEnabled,
                title = stringResource(R.string.set_use_headset_button),
                subtitle = stringResource(R.string.set_headset_button_sub),
                onChange = { v -> onChange { it.copy(headsetButtonEnabled = v) } },
            )
            Text(
                if (lastKeyCode == 0)
                    stringResource(R.string.set_keycode_hint)
                else stringResource(R.string.set_keycode_last, lastKeyCode),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            Text(
                stringResource(R.string.set_headset_differ),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
        }

        SectionCard(title = stringResource(R.string.set_remote_buttons)) {
            Text(
                stringResource(R.string.set_remote_buttons_sub),
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
                        .heightIn(min = Comfort.MIN_TOUCH_DP.dp)
                        .clickable(role = Role.Button, onClick = {
                            onChange { it.copy(buttons = it.buttons.with(input, action.next())) }
                        })
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(input.labelRes()), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(action.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Accent,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
            ToggleRow(
                checked = settings.volumeKeys,
                title = stringResource(R.string.set_volume_keys),
                subtitle = stringResource(R.string.set_volume_keys_sub),
                onChange = { v -> onChange { it.copy(volumeKeys = v) } },
            )
            SecondaryButton(
                text = stringResource(R.string.set_reset_buttons),
                onClick = { onChange { it.copy(buttons = ButtonMapping()) } },
                height = 46.dp,
            )
        }

        SectionCard(title = stringResource(R.string.set_music_tones)) {
            ToggleRow(
                checked = settings.duckWhenITalk,
                title = stringResource(R.string.set_duck_own),
                subtitle = stringResource(R.string.set_duck_own_sub),
                onChange = { v -> onChange { it.copy(duckWhenITalk = v) } },
            )
            ToggleRow(
                checked = settings.linkTones,
                title = stringResource(R.string.set_link_tones),
                subtitle = stringResource(R.string.set_link_tones_sub),
                onChange = { v -> onChange { it.copy(linkTones = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_location)) {
            ToggleRow(
                checked = settings.shareLocation,
                title = stringResource(R.string.set_share_location),
                subtitle = stringResource(R.string.set_share_location_sub),
                onChange = onShareLocation,
            )
            if (locationDenied) {
                Text(
                    stringResource(R.string.set_location_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.Buddy,
                )
                SecondaryButton(stringResource(R.string.set_open_app_settings), onOpenAppSettings)
            }
            Text(stringResource(R.string.set_distances_in), style = MaterialTheme.typography.bodyLarge)
            DistanceUnit.entries.forEach { u ->
                ChoiceRow(
                    selected = settings.distanceUnit == u,
                    title = when (u) {
                        DistanceUnit.Kilometres -> stringResource(R.string.set_unit_km)
                        DistanceUnit.Miles -> stringResource(R.string.set_unit_mi)
                    },
                    subtitle = "",
                    onClick = { onChange { it.copy(distanceUnit = u) } },
                )
            }
            ToggleRow(
                checked = settings.fallingBehindAlert,
                title = stringResource(R.string.set_behind_warn),
                subtitle = stringResource(R.string.set_behind_warn_sub),
                onChange = { v -> onChange { it.copy(fallingBehindAlert = v) } },
            )
            if (settings.fallingBehindAlert) {
                val far = DistanceFormat.format(
                    settings.fallingBehindMeters.toDouble(), settings.distanceUnit,
                ).text
                Text(stringResource(R.string.set_behind_farther, far), style = MaterialTheme.typography.bodyLarge)
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
                    stringResource(R.string.set_behind_seconds, settings.fallingBehindSeconds),
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

        SectionCard(title = stringResource(R.string.set_ride_security)) {
            Text(
                stringResource(R.string.set_ride_security_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            ToggleRow(
                checked = settings.allowLegacyBuddy,
                title = stringResource(R.string.set_allow_legacy),
                subtitle = stringResource(R.string.set_allow_legacy_sub),
                onChange = { v -> onChange { it.copy(allowLegacyBuddy = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_keep_alive)) {
            SecondaryButton(stringResource(R.string.set_keep_alive_sub), onOem)
        }

        SectionCard(title = stringResource(R.string.set_hardware)) {
            SecondaryButton(stringResource(R.string.set_hfp_bridge), onHfp)
        }

        SectionCard(title = stringResource(R.string.set_run_setup)) {
            Text(
                stringResource(R.string.set_run_setup_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            SecondaryButton(stringResource(R.string.set_run_setup), onRunSetup)
        }

        SectionCard(title = stringResource(R.string.inet_title)) {
            InternetRideSection(settings = settings, onChange = onChange)
        }

        SectionCard(title = stringResource(R.string.upd_title)) {
            UpdateSection(
                state = updateState,
                appVersion = appVersion,
                autoCheck = settings.autoUpdateCheck,
                callActive = callActive,
                actions = updateActions,
                onAutoCheck = { v -> onChange { it.copy(autoUpdateCheck = v) } },
            )
        }

        SectionCard(title = stringResource(R.string.set_diagnostics)) {
            Text(
                stringResource(R.string.set_diagnostics_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            SecondaryButton(
                text = stringResource(if (shareLogBusy) R.string.set_share_log_busy else R.string.set_share_log),
                onClick = onShareLog,
                enabled = !shareLogBusy,
            )
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
            .heightIn(min = Comfort.MIN_TOUCH_DP.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            // The row carries the click, so a screen reader announces one radio button, not two.
            onClick = null,
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
internal fun ToggleRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Comfort.MIN_TOUCH_DP.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
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
            // The row above carries the toggle, so the whole line is one target for TalkBack.
            onCheckedChange = null,
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
