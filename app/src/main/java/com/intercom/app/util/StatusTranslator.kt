package com.intercom.app.util

import com.intercom.app.R

/**
 * Turns the English status lines the transports write into the rider's language.
 *
 * The transports ([com.intercom.app.transport.RfcommLink], WifiDirectLink, LinkSelector and
 * the group link) stay free of Android resources on purpose: they are the code that must
 * keep working, and their text doubles as the log. So the text is matched here, where it is
 * copied into the call status, and anything that does not match is shown as it came - an
 * untranslated line is better than a blank one. `StatusTranslatorTest` scans the transport
 * sources, so adding a status line there without a rule here fails the build.
 */
object StatusTranslator {

    class Rule(pattern: String, @androidx.annotation.StringRes val res: Int, val nested: Set<Int> = emptySet()) {
        val regex = Regex(pattern)
    }

    /** What a line resolves to: a string resource and its arguments (already translated where nested). */
    class Resolved(val res: Int, val args: List<String>)

    /** Order matters: the specific rules come before the catch-alls such as "Connected to X". */
    private val rules: List<Rule> = listOf(
        Rule("Call ended", R.string.st_call_ended),
        Rule("Buddy moved to (.+)", R.string.st_buddy_moved),
        Rule("Moving to (.+?) - (.+)", R.string.st_moving, nested = setOf(2)),
        Rule("Retrying in (\\d+)s \\(attempt (\\d+)\\)", R.string.st_retrying),
        Rule("Handshaking", R.string.st_handshaking),
        Rule("Connected to 1 rider", R.string.st_group_one),
        Rule("Connected to (\\d+) riders", R.string.st_group_many),
        Rule("Connected to (.+)", R.string.st_connected_to),
        Rule("Link error: (.*)", R.string.st_link_error),
        Rule("Waiting for buddy", R.string.st_waiting_buddy),
        Rule("Listen failed: (.*)", R.string.st_listen_failed),
        Rule("Connecting to (.+)", R.string.st_connecting_to),
        Rule("Not paired - pair in Bluetooth settings first", R.string.st_not_paired),
        Rule("(.+) attempt failed: (.*)", R.string.st_attempt_failed),
        Rule("Audio format mismatch with buddy", R.string.st_format_mismatch),
        Rule("Handshake timed out", R.string.st_handshake_timeout),
        Rule("Handshake failed: (.*)", R.string.st_handshake_failed),
        Rule("Buddy ended the call", R.string.st_buddy_ended),
        Rule("Link lost", R.string.st_link_lost),
        Rule("No reply for (\\d+)s - reconnecting", R.string.st_no_reply),
        Rule("This phone has no Wi-Fi Direct", R.string.st_no_wifi_direct),
        Rule("Nearby Wi-Fi permission is not granted", R.string.st_nearby_wifi_perm),
        Rule("Wi-Fi is off - Wi-Fi Direct needs it on, even with no network", R.string.st_wifi_off),
        Rule("Wi-Fi Direct is disabled on this phone", R.string.st_p2p_disabled),
        Rule("Wi-Fi Direct closed", R.string.st_wd_closed),
        Rule("Creating Wi-Fi group", R.string.st_creating_group),
        Rule("Looking for the group", R.string.st_looking_group),
        Rule("(.+) - staying on Bluetooth", R.string.st_group_stay_bt, nested = setOf(1)),
        Rule("(.+) - retrying in (\\d+)s", R.string.st_group_retry, nested = setOf(1)),
        Rule("Wi-Fi Direct to (.+)", R.string.st_wd_to),
        Rule("Wi-Fi Direct error: (.*)", R.string.st_wd_error),
        Rule("Version mismatch over Wi-Fi Direct", R.string.st_wd_version),
        Rule("Wi-Fi Direct link lost", R.string.st_wd_lost),
        Rule("No reply over Wi-Fi Direct for (\\d+)s", R.string.st_wd_no_reply),
        Rule("Wi-Fi Direct could not form a group", R.string.st_wd_no_group),
        Rule("Wi-Fi Direct did not come up in time", R.string.st_wd_timeout),
        Rule("Could not create the Wi-Fi group", R.string.st_create_failed),
        Rule("Group requested but never formed", R.string.st_never_formed),
        Rule("Could not create the Wi-Fi group - (.+)", R.string.st_create_failed_why, nested = setOf(1)),
        Rule("Could not join the Wi-Fi group - (.+)", R.string.st_join_failed_why, nested = setOf(1)),
        Rule("Could not find the host's Wi-Fi group", R.string.st_host_group_missing),
        Rule("this phone does not support Wi-Fi Direct", R.string.st_why_unsupported),
        Rule("the Wi-Fi Direct framework was busy", R.string.st_why_busy),
        Rule("no service requests", R.string.st_why_no_requests),
        Rule("an internal Wi-Fi error", R.string.st_why_internal),
        Rule("code (\\d+)", R.string.st_why_code),
        Rule("frame loss stayed high", R.string.st_reason_loss),
        Rule("the link stayed down", R.string.st_reason_down),
        Rule("the rider's transport preference", R.string.st_reason_pref),
        Rule("Not started", R.string.st_not_started),
        Rule("Group mode needs Google Play services", R.string.st_play_services),
        Rule("Looking for riders", R.string.st_looking_riders),
        Rule("Securing connection", R.string.st_securing_conn),
        Rule("Could not advertise: (.*)", R.string.st_could_not_advertise),
        Rule("Could not search for riders: (.*)", R.string.st_could_not_search),
        Rule("Microphone changed - reconnecting", R.string.st_mic_changed),
        Rule("Microphone lost", R.string.st_mic_lost),
        Rule("Audio output changed - reconnecting", R.string.st_out_changed),
        Rule("Audio output lost", R.string.st_out_lost),
        Rule("AI noise reduction failed - using standard", R.string.st_ai_failed),
        Rule("AI noise reduction is too heavy for this phone - using standard", R.string.st_ai_heavy),
        Rule("AI noise reduction is not available on this phone - using standard", R.string.st_ai_unavailable),
    )

    /**
     * Pure lookup, so it can be tested without a [android.content.Context].
     * [format] renders a resource id with its arguments in the chosen language.
     */
    fun resolve(status: String, format: (Int, List<String>) -> String): String? {
        for (rule in rules) {
            val m = rule.regex.matchEntire(status) ?: continue
            val args = (1..m.groupValues.lastIndex).map { i ->
                val raw = m.groupValues[i]
                if (i in rule.nested) resolve(raw, format) ?: raw else raw
            }
            return format(rule.res, args)
        }
        return null
    }

    fun translate(context: android.content.Context, status: String): String =
        resolve(status) { res, args -> context.getString(res, *args.toTypedArray()) } ?: status

    /** True when some rule recognises [status]; used by the source-scanning test. */
    fun isKnown(status: String): Boolean = resolve(status) { _, _ -> "" } != null
}
