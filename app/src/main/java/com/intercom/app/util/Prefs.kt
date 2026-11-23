package com.intercom.app.util

import android.content.Context
import android.content.SharedPreferences
import com.intercom.app.audio.MicSource
import com.intercom.app.audio.NoiseEngine
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.audio.WindCutFilter
import com.intercom.app.floor.VadSensitivity
import com.intercom.app.media.ButtonAction
import com.intercom.app.media.ButtonInput
import com.intercom.app.media.ButtonMapping
import com.intercom.app.location.DistanceUnit
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the rider takes the floor.
 *
 * [Open] is the default and the point of the product: a continuous call where speaking
 * is all it takes. [PushToTalk] is for riders who would rather hold a button than trust
 * a detector at speed, or whose helmet is too noisy for one.
 */
enum class TalkMode(val label: String) {
    Open("Open call"),
    PushToTalk("Push to talk"),
}

/** Which transport the rider wants the link to use. */
enum class TransportPreference { Auto, Bluetooth, Range }

/** Everything the rider can change, in one place. */
data class Settings(
    val riderName: String = "Rider",
    val buddyAddress: String? = null,
    val buddyName: String? = null,
    val transport: TransportPreference = TransportPreference.Auto,
    val talkMode: TalkMode = TalkMode.Open,
    val micSource: MicSource = MicSource.Phone,
    val noiseSuppression: NoiseSuppression = NoiseSuppression.Light,
    /**
     * Which denoiser does the work. [NoiseEngine.Standard] is the default; [NoiseEngine.Ai] is
     * RNNoise, which has no strength setting of its own (Off still turns it off).
     */
    val noiseEngine: NoiseEngine = NoiseEngine.Standard,
    /**
     * Pick Light/Medium/Strong from GPS speed while riding. Needs "Share my location"; with no
     * fix the preset above is used. Choosing a preset by hand turns this off.
     */
    val autoNoise: Boolean = false,
    /** Wind low-cut frequency in Hz; 0 turns it off. The default is the long-standing 150 Hz. */
    val windCutHz: Int = WindCutFilter.DEFAULT_HZ,
    /** Fourth-order (steeper) wind low-cut instead of second-order. */
    val windCutSteep: Boolean = false,
    /** Slow automatic gain control and limiter on the voice you send. Off by default. */
    val agc: Boolean = false,
    val vadSensitivity: VadSensitivity = VadSensitivity.Medium,
    /** Voice level in dB, -12..+12, applied on top of the stream. */
    val voiceLevelDb: Int = 0,
    val beepWhenMuted: Boolean = true,
    val takeAudioFocus: Boolean = true,
    val headsetButtonEnabled: Boolean = true,
    /**
     * Hidden: use AudioRecord/AudioTrack instead of Oboe, in case Oboe misbehaves on some
     * OEM. No row in Settings; long-press the Settings title to flip it.
     */
    val legacyAudioPath: Boolean = false,
    /**
     * The shared ride passphrase, in [com.intercom.app.security.RideCode.canonical] form, or
     * empty when none is set. Kept in a separate preferences file that is excluded from
     * backup (see [Prefs]).
     */
    val rideCode: String = "",
    /**
     * Talk to a buddy whose app cannot encrypt, unprotected. Off by default; when on, the
     * call screen shows a warning for as long as the call is open.
     */
    val allowLegacyBuddy: Boolean = false,
    /** Opt in to group mode (three or more riders over Nearby Connections). Off by default. */
    val groupRide: Boolean = false,
    /**
     * "Share my location with riders". Off by default, and while it is off the phone does not
     * read its position, does not send one and ignores any it is sent. The feature needs
     * location permission, which is only asked for when this is switched on.
     */
    val shareLocation: Boolean = false,
    val distanceUnit: DistanceUnit = DistanceUnit.Kilometres,
    /** Beep when a rider stays beyond [fallingBehindMeters] for [fallingBehindSeconds]. */
    val fallingBehindAlert: Boolean = false,
    val fallingBehindMeters: Int = 500,
    val fallingBehindSeconds: Int = 20,
    /** What each headset or remote button gesture does. */
    val buttons: ButtonMapping = ButtonMapping(),
    /**
     * Use the phone's volume keys (and a Bluetooth remote's) as push-to-talk and mute during a
     * call. Opt-in, because while it is on the volume buttons stop changing the volume.
     */
    val volumeKeys: Boolean = false,
    /** Lower the music while I am talking too, not only while the buddy is. */
    val duckWhenITalk: Boolean = false,
    /** Play a short tone when the link drops and another when it comes back. */
    val linkTones: Boolean = true,
    /** How the last call started, for "Reconnect": [LastRole.Host] or [LastRole.Client]. */
    val lastRole: LastRole = LastRole.None,
    /** The language of the app. [AppLanguage.System] follows the phone. */
    val language: AppLanguage = AppLanguage.System,
    /** Brighter text and borders, for glare. */
    val highContrast: Boolean = false,
    /** Opt-in: look for a newer release once a day when the app opens. Off by default. */
    val autoUpdateCheck: Boolean = false,
    /** Group rides go through a relay over mobile data instead of Nearby. Off by default. */
    val internetRide: Boolean = false,
    /** The relay's `wss://` address. Empty until the rider sets one; the app has no default server. */
    val relayUrl: String = "",
    /** Larger buttons with more space between them, for riding gloves. */
    val largeButtons: Boolean = false,
)

/** The side of the last two-rider call, remembered so the Start screen can offer to redo it. */
enum class LastRole { None, Host, Client }

/** Thin SharedPreferences wrapper that publishes the whole [Settings] as a flow. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The ride code lives in its own file so it can be excluded from Android's cloud and
     * device-transfer backup (res/xml/backup_rules.xml, data_extraction_rules.xml). A secret
     * that follows the account to a new phone, or sits in a Google Drive backup, is not a
     * secret shared only by the riders on the road.
     */
    private val secrets: SharedPreferences =
        context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    val current: Settings get() = _settings.value

    private fun load(): Settings = Settings(
        riderName = sp.getString(K_NAME, "Rider") ?: "Rider",
        buddyAddress = sp.getString(K_BUDDY_ADDR, null),
        buddyName = sp.getString(K_BUDDY_NAME, null),
        transport = enumOf(sp.getString(K_TRANSPORT, null), TransportPreference.Auto),
        talkMode = enumOf(sp.getString(K_TALK, null), TalkMode.Open),
        micSource = enumOf(sp.getString(K_MIC, null), MicSource.Phone),
        noiseSuppression = enumOf(sp.getString(K_NS, null), NoiseSuppression.Light),
        noiseEngine = enumOf(sp.getString(K_NOISE_ENGINE, null), NoiseEngine.Standard),
        autoNoise = sp.getBoolean(K_AUTO_NOISE, false),
        windCutHz = WindCutFilter.clampCutoff(sp.getInt(K_WIND_CUT, WindCutFilter.DEFAULT_HZ)),
        windCutSteep = sp.getBoolean(K_WIND_STEEP, false),
        agc = sp.getBoolean(K_AGC, false),
        vadSensitivity = enumOf(sp.getString(K_VAD, null), VadSensitivity.Medium),
        voiceLevelDb = sp.getInt(K_VOICE_DB, 0),
        beepWhenMuted = sp.getBoolean(K_BEEP, true),
        takeAudioFocus = sp.getBoolean(K_FOCUS, true),
        headsetButtonEnabled = sp.getBoolean(K_BUTTON, true),
        legacyAudioPath = sp.getBoolean(K_LEGACY_AUDIO, false),
        rideCode = secrets.getString(K_RIDE_CODE, "") ?: "",
        allowLegacyBuddy = sp.getBoolean(K_ALLOW_LEGACY, false),
        groupRide = sp.getBoolean(K_GROUP_RIDE, false),
        shareLocation = sp.getBoolean(K_SHARE_LOCATION, false),
        distanceUnit = enumOf(
            sp.getString(K_DISTANCE_UNIT, null),
            DistanceUnit.defaultFor(Locale.getDefault().country),
        ),
        fallingBehindAlert = sp.getBoolean(K_BEHIND_ALERT, false),
        fallingBehindMeters = sp.getInt(K_BEHIND_M, 500),
        fallingBehindSeconds = sp.getInt(K_BEHIND_S, 20),
        buttons = ButtonMapping(
            single = buttonOf(K_BTN_SINGLE, ButtonAction.None),
            double = buttonOf(K_BTN_DOUBLE, ButtonAction.PushToTalk),
            triple = buttonOf(K_BTN_TRIPLE, ButtonAction.Mute),
            long = buttonOf(K_BTN_LONG, ButtonAction.EndCall),
            volumeUp = buttonOf(K_BTN_VOL_UP, ButtonAction.PushToTalk),
            volumeDown = buttonOf(K_BTN_VOL_DOWN, ButtonAction.Mute),
        ),
        volumeKeys = sp.getBoolean(K_VOLUME_KEYS, false),
        duckWhenITalk = sp.getBoolean(K_DUCK_OWN, false),
        linkTones = sp.getBoolean(K_LINK_TONES, true),
        lastRole = enumOf(sp.getString(K_LAST_ROLE, null), LastRole.None),
        language = AppLanguage.parse(sp.getString(K_LANGUAGE, null)),
        highContrast = sp.getBoolean(K_HIGH_CONTRAST, false),
        autoUpdateCheck = sp.getBoolean(K_AUTO_UPDATE, false),
        internetRide = sp.getBoolean(K_INTERNET_RIDE, false),
        relayUrl = sp.getString(K_RELAY_URL, "") ?: "",
        largeButtons = sp.getBoolean(K_LARGE_BUTTONS, false),
    )

    private fun buttonOf(key: String, fallback: ButtonAction): ButtonAction =
        enumOf(sp.getString(key, null), fallback)

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    fun update(block: (Settings) -> Settings) {
        val next = block(_settings.value)
        sp.edit().apply {
            putString(K_NAME, next.riderName)
            putString(K_BUDDY_ADDR, next.buddyAddress)
            putString(K_BUDDY_NAME, next.buddyName)
            putString(K_TRANSPORT, next.transport.name)
            putString(K_TALK, next.talkMode.name)
            putString(K_MIC, next.micSource.name)
            putString(K_NS, next.noiseSuppression.name)
            putString(K_NOISE_ENGINE, next.noiseEngine.name)
            putBoolean(K_AUTO_NOISE, next.autoNoise)
            putInt(K_WIND_CUT, next.windCutHz)
            putBoolean(K_WIND_STEEP, next.windCutSteep)
            putBoolean(K_AGC, next.agc)
            putString(K_VAD, next.vadSensitivity.name)
            putInt(K_VOICE_DB, next.voiceLevelDb)
            putBoolean(K_BEEP, next.beepWhenMuted)
            putBoolean(K_FOCUS, next.takeAudioFocus)
            putBoolean(K_BUTTON, next.headsetButtonEnabled)
            putBoolean(K_LEGACY_AUDIO, next.legacyAudioPath)
            putBoolean(K_ALLOW_LEGACY, next.allowLegacyBuddy)
            putBoolean(K_GROUP_RIDE, next.groupRide)
            putBoolean(K_SHARE_LOCATION, next.shareLocation)
            putString(K_DISTANCE_UNIT, next.distanceUnit.name)
            putBoolean(K_BEHIND_ALERT, next.fallingBehindAlert)
            putInt(K_BEHIND_M, next.fallingBehindMeters)
            putInt(K_BEHIND_S, next.fallingBehindSeconds)
            for (input in ButtonInput.entries) putString(buttonKey(input), next.buttons.actionFor(input).name)
            putBoolean(K_VOLUME_KEYS, next.volumeKeys)
            putBoolean(K_DUCK_OWN, next.duckWhenITalk)
            putBoolean(K_LINK_TONES, next.linkTones)
            putString(K_LAST_ROLE, next.lastRole.name)
            putString(K_LANGUAGE, next.language.name)
            putBoolean(K_HIGH_CONTRAST, next.highContrast)
            putBoolean(K_AUTO_UPDATE, next.autoUpdateCheck)
            putBoolean(K_INTERNET_RIDE, next.internetRide)
            putString(K_RELAY_URL, next.relayUrl)
            putBoolean(K_LARGE_BUTTONS, next.largeButtons)
        }.apply()
        secrets.edit().putString(K_RIDE_CODE, next.rideCode).apply()
        _settings.value = next
    }

    /** Set once, after the rider has been shown the OEM background-permission steps. */
    var oemGuidanceShown: Boolean
        get() = sp.getBoolean(K_OEM_SHOWN, false)
        set(v) { sp.edit().putBoolean(K_OEM_SHOWN, v).apply() }

    /** Set once the first-run walkthrough has been finished or skipped. */
    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) { sp.edit().putBoolean(K_ONBOARDED, v).apply() }

    /** When the update check last ran (0 = never); the daily opt-in check uses it. */
    var lastUpdateCheckMs: Long
        get() = sp.getLong(K_LAST_UPDATE_CHECK, 0L)
        set(v) { sp.edit().putLong(K_LAST_UPDATE_CHECK, v).apply() }

    private fun buttonKey(input: ButtonInput): String = when (input) {
        ButtonInput.Single -> K_BTN_SINGLE
        ButtonInput.Double -> K_BTN_DOUBLE
        ButtonInput.Triple -> K_BTN_TRIPLE
        ButtonInput.Long -> K_BTN_LONG
        ButtonInput.VolumeUp -> K_BTN_VOL_UP
        ButtonInput.VolumeDown -> K_BTN_VOL_DOWN
    }

    companion object {
        /** The stored language name, read without building a [Prefs] (for attachBaseContext). */
        fun rawLanguage(context: Context): String? =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(K_LANGUAGE, null)

        private const val FILE = "rider_intercom"
        private const val K_LANGUAGE = "language"
        private const val K_HIGH_CONTRAST = "high_contrast"
        private const val K_LARGE_BUTTONS = "large_buttons"
        private const val K_ONBOARDED = "onboarded"
        private const val K_AUTO_UPDATE = "auto_update_check"
        private const val K_INTERNET_RIDE = "internet_ride"
        private const val K_RELAY_URL = "relay_url"
        private const val K_LAST_UPDATE_CHECK = "last_update_check"
        private const val K_BTN_SINGLE = "btn_single"
        const val K_BTN_DOUBLE = "btn_double"
        const val K_BTN_TRIPLE = "btn_triple"
        const val K_BTN_LONG = "btn_long"
        const val K_BTN_VOL_UP = "btn_vol_up"
        const val K_BTN_VOL_DOWN = "btn_vol_down"
        const val K_VOLUME_KEYS = "volume_keys"
        const val K_DUCK_OWN = "duck_own"
        const val K_LINK_TONES = "link_tones"
        const val K_LAST_ROLE = "last_role"
        const val K_NAME = "rider_name"
        const val K_BUDDY_ADDR = "buddy_addr"
        const val K_BUDDY_NAME = "buddy_name"
        const val K_TRANSPORT = "transport"
        const val K_TALK = "talk_mode"
        const val K_MIC = "mic_source"
        const val K_NS = "noise_suppression"
        const val K_NOISE_ENGINE = "noise_engine"
        const val K_AUTO_NOISE = "auto_noise"
        const val K_WIND_CUT = "wind_cut_hz"
        const val K_WIND_STEEP = "wind_cut_steep"
        const val K_AGC = "agc"
        const val K_VAD = "vad"
        const val K_VOICE_DB = "voice_db"
        const val K_BEEP = "beep"
        const val K_FOCUS = "focus"
        const val K_BUTTON = "button"
        const val K_LEGACY_AUDIO = "legacy_audio"
        const val K_OEM_SHOWN = "oem_shown"
        const val K_ALLOW_LEGACY = "allow_legacy_buddy"
        const val K_GROUP_RIDE = "group_ride"
        const val K_RIDE_CODE = "ride_code"
        const val K_SHARE_LOCATION = "share_location"
        const val K_DISTANCE_UNIT = "distance_unit"
        const val K_BEHIND_ALERT = "behind_alert"
        const val K_BEHIND_M = "behind_m"
        const val K_BEHIND_S = "behind_s"
        const val SECRETS_FILE = "rider_intercom_secret"
    }
}
