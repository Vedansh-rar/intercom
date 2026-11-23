package com.intercom.app.ui

import com.intercom.app.util.AppLanguage
import androidx.annotation.StringRes
import com.intercom.app.R
import com.intercom.app.audio.MicSource
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.floor.VadSensitivity
import com.intercom.app.media.ButtonAction
import com.intercom.app.media.ButtonInput
import com.intercom.app.transport.LinkState
import com.intercom.app.transport.Transport
import com.intercom.app.util.TalkMode
import com.intercom.app.util.TransportPreference

/**
 * The words for every setting a rider can pick, as string resources. The enums keep their
 * English `label` for logs; anything shown on screen or in a notification goes through here,
 * so a new language is a new strings file and nothing else.
 */
@StringRes fun NoiseSuppression.labelRes(): Int = when (this) {
    NoiseSuppression.Off -> R.string.common_off
    NoiseSuppression.Light -> R.string.noise_light
    NoiseSuppression.Medium -> R.string.noise_medium
    NoiseSuppression.Strong -> R.string.noise_strong
}

@StringRes fun NoiseSuppression.subtitleRes(): Int = when (this) {
    NoiseSuppression.Off -> R.string.noise_sub_off
    NoiseSuppression.Light -> R.string.noise_sub_light
    NoiseSuppression.Medium -> R.string.noise_sub_medium
    NoiseSuppression.Strong -> R.string.noise_sub_strong
}

@StringRes fun MicSource.titleRes(): Int = when (this) {
    MicSource.Wired -> R.string.mic_title_wired
    MicSource.Phone -> R.string.mic_title_phone
    MicSource.LeAudio -> R.string.mic_title_le
    MicSource.HeadsetHfp -> R.string.mic_title_hfp
}

@StringRes fun MicSource.subtitleRes(): Int = when (this) {
    MicSource.Wired -> R.string.mic_sub_wired
    MicSource.Phone -> R.string.mic_sub_phone
    MicSource.LeAudio -> R.string.mic_sub_le
    MicSource.HeadsetHfp -> R.string.mic_sub_hfp
}

@StringRes fun TalkMode.labelRes(): Int = when (this) {
    TalkMode.Open -> R.string.talk_open
    TalkMode.PushToTalk -> R.string.talk_ptt
}

@StringRes fun VadSensitivity.labelRes(): Int = when (this) {
    VadSensitivity.Low -> R.string.vad_low
    VadSensitivity.Medium -> R.string.noise_medium
    VadSensitivity.High -> R.string.vad_high
}

@StringRes fun TransportPreference.labelRes(): Int = when (this) {
    TransportPreference.Auto -> R.string.tp_auto
    TransportPreference.Bluetooth -> R.string.tp_bluetooth
    TransportPreference.Range -> R.string.tp_range
}

@StringRes fun Transport.labelRes(): Int = when (this) {
    Transport.Bluetooth -> R.string.transport_bluetooth
    Transport.WifiDirect -> R.string.transport_wifi
    Transport.Nearby -> R.string.transport_nearby
}

@StringRes fun LinkState.labelRes(): Int = when (this) {
    LinkState.Idle -> R.string.ls_idle
    LinkState.Listening -> R.string.ls_listening
    LinkState.Connecting -> R.string.ls_connecting
    LinkState.Handshaking -> R.string.ls_handshaking
    LinkState.Connected -> R.string.ls_connected
    LinkState.Reconnecting -> R.string.ls_reconnecting
    LinkState.Failed -> R.string.ls_failed
    LinkState.Closed -> R.string.ls_closed
}

@StringRes fun ButtonAction.labelRes(): Int = when (this) {
    ButtonAction.None -> R.string.ba_none
    ButtonAction.PushToTalk -> R.string.ba_ptt
    ButtonAction.Mute -> R.string.ba_mute
    ButtonAction.Deafen -> R.string.ba_deafen
    ButtonAction.CycleNoise -> R.string.ba_noise
    ButtonAction.EndCall -> R.string.ba_end
}

@StringRes fun ButtonInput.labelRes(): Int = when (this) {
    ButtonInput.Single -> R.string.bi_single
    ButtonInput.Double -> R.string.bi_double
    ButtonInput.Triple -> R.string.bi_triple
    ButtonInput.Long -> R.string.bi_long
    ButtonInput.VolumeUp -> R.string.bi_vol_up
    ButtonInput.VolumeDown -> R.string.bi_vol_down
}

/** Language names in the picker: the two real languages are written in themselves. */
@StringRes fun AppLanguage.titleRes(): Int = when (this) {
    AppLanguage.System -> R.string.set_language_system
    AppLanguage.English -> R.string.lang_english
    AppLanguage.Hindi -> R.string.lang_hindi
}
