package com.intercom.app.util

import android.content.Context
import android.content.SharedPreferences
import com.intercom.app.audio.MicSource
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.floor.VadSensitivity
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
)

/** Thin SharedPreferences wrapper that publishes the whole [Settings] as a flow. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("rider_intercom", Context.MODE_PRIVATE)

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
    )

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
        }.apply()
        secrets.edit().putString(K_RIDE_CODE, next.rideCode).apply()
        _settings.value = next
    }

    /** Set once, after the rider has been shown the OEM background-permission steps. */
    var oemGuidanceShown: Boolean
        get() = sp.getBoolean(K_OEM_SHOWN, false)
        set(v) { sp.edit().putBoolean(K_OEM_SHOWN, v).apply() }

    private companion object {
        const val K_NAME = "rider_name"
        const val K_BUDDY_ADDR = "buddy_addr"
        const val K_BUDDY_NAME = "buddy_name"
        const val K_TRANSPORT = "transport"
        const val K_TALK = "talk_mode"
        const val K_MIC = "mic_source"
        const val K_NS = "noise_suppression"
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
