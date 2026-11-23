package com.intercom.app.i18n

import com.intercom.app.R
import com.intercom.app.transport.SwitchReason
import com.intercom.app.util.StatusTranslator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTranslatorTest {

    /** Renders "<resource id>[arg|arg]" so a test can see exactly what was chosen. */
    private val fmt: (Int, List<String>) -> String = { res, args -> "$res" + args.joinToString("|", "[", "]") }

    private fun resolve(s: String) = StatusTranslator.resolve(s, fmt)

    @Test fun plain_lines_resolve() {
        assertEquals("${R.string.st_handshaking}[]", resolve("Handshaking"))
        assertEquals("${R.string.st_link_lost}[]", resolve("Link lost"))
    }

    @Test fun arguments_are_extracted() {
        assertEquals("${R.string.st_no_reply}[7]", resolve("No reply for 7s - reconnecting"))
        assertEquals("${R.string.st_retrying}[4|2]", resolve("Retrying in 4s (attempt 2)"))
        assertEquals("${R.string.st_connected_to}[Asha]", resolve("Connected to Asha"))
    }

    @Test fun group_counts_beat_the_generic_connected_rule() {
        assertEquals("${R.string.st_group_one}[]", resolve("Connected to 1 rider"))
        assertEquals("${R.string.st_group_many}[3]", resolve("Connected to 3 riders"))
    }

    @Test fun switch_reasons_are_translated_inside_the_sentence() {
        val inner = resolve("frame loss stayed high")!!
        assertEquals(
            "${R.string.st_moving}[Wi-Fi Direct|$inner]",
            resolve("Moving to Wi-Fi Direct - frame loss stayed high"),
        )
    }

    @Test fun group_errors_nest_their_cause() {
        val why = resolve("the Wi-Fi Direct framework was busy")!!
        assertEquals(
            "${R.string.st_create_failed_why}[$why]",
            resolve("Could not create the Wi-Fi group - the Wi-Fi Direct framework was busy"),
        )
        val base = resolve("Could not create the Wi-Fi group")!!
        assertEquals("${R.string.st_group_stay_bt}[$base]", resolve("Could not create the Wi-Fi group - staying on Bluetooth"))
    }

    @Test fun a_rider_called_like_a_status_is_not_translated() {
        // Names are arguments, never re-matched against the table.
        assertEquals("${R.string.st_connected_to}[Handshaking]", resolve("Connected to Handshaking"))
    }

    @Test fun unknown_text_is_left_alone() {
        assertNull(resolve("Something nobody wrote a rule for"))
        assertNull(resolve(""))
    }

    @Test fun every_switch_reason_has_a_rule() {
        for (r in SwitchReason.entries) {
            assertTrue("no rule for ${r.name}: ${r.explain}", StatusTranslator.isKnown(r.explain))
        }
    }

    // ------------------------------------------------------------------ source scan

    private val sources = listOf(
        "transport/RfcommLink.kt",
        "transport/WifiDirectLink.kt",
        "transport/LinkSelector.kt",
        "group/GroupLink.kt",
        "audio/MicCapture.kt",
        "audio/VoicePlayer.kt",
    )

    private fun mainDir(): File {
        val rel = "src/main/java/com/intercom/app"
        return listOf(File(rel), File("app/$rel")).first { it.isDirectory }
    }

    /**
     * Fails when someone adds a status line to a transport without a rule here, which would
     * otherwise show English to a rider who chose Hindi and nobody would notice.
     */
    @Test fun every_status_literal_in_the_transports_has_a_rule() {
        val linePattern = Regex("""(_status\.value\s*=|statusFlow\.value\s*=|lastGroupError\s*=|onStatus\(|giveUpOnAi\(|\bfail\(|^\s*else\s+"|->\s*"|return\s+")""")
        val interpolation = Regex("""\$\{(?:[^{}]|\{[^{}]*\})*\}|\$\w+""")
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val unknown = mutableListOf<String>()
        var checked = 0
        for (name in sources) {
            File(mainDir(), name).readLines().forEachIndexed { i, line ->
                if ("Log." in line || !linePattern.containsMatchIn(line)) return@forEachIndexed
                for (m in literal.findAll(interpolation.replace(line, "1"))) {
                    val text = m.groupValues[1]
                    if (text.none { it.isLetter() }) continue
                    checked++
                    if (!StatusTranslator.isKnown(text)) unknown += "$name:${i + 1}  \"$text\""
                }
            }
        }
        assertTrue("scan found nothing; the pattern is stale", checked > 30)
        assertTrue("status lines without a StatusTranslator rule:\n" + unknown.joinToString("\n"), unknown.isEmpty())
    }
}
