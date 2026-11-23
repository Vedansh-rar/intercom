package com.intercom.app.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "no hard-coded user-facing strings" rule and keeps the translations in step
 * with the English source. These run on the JVM, so they read the files directly.
 */
class StringResourcesTest {

    private fun moduleDir(): File = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private val res get() = File(moduleDir(), "src/main/res")
    private val kotlinDir get() = File(moduleDir(), "src/main/java")

    private val entry = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d\$[sd]""")

    private fun load(dir: String): Map<String, String> =
        entry.findAll(File(res, "$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val english get() = load("values")
    private val hindi get() = load("values-hi")

    @Test fun hindi_has_every_english_string_and_nothing_extra() {
        val en = english.keys - "app_name"
        val hi = hindi.keys - "app_name"
        assertEquals("missing in Hindi", emptySet<String>(), en - hi)
        assertEquals("only in Hindi", emptySet<String>(), hi - en)
    }

    @Test fun placeholders_match_between_languages() {
        val bad = english.filterKeys { it in hindi }.filter { (k, v) ->
            placeholder.findAll(v).map { it.value }.toSortedSet() != placeholder.findAll(hindi.getValue(k)).map { it.value }.toSortedSet()
        }.keys
        assertEquals("placeholder mismatch", emptySet<String>(), bad.toSet())
    }

    @Test fun no_translation_is_blank() {
        assertTrue(english.filterValues { it.isBlank() }.isEmpty())
        assertTrue(hindi.filterValues { it.isBlank() }.isEmpty())
    }

    @Test fun every_string_the_code_asks_for_exists() {
        val used = kotlinDir.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""R\.string\.(\w+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        val missing = used - english.keys - "app_name"
        assertEquals("R.string ids with no English text", emptySet<String>(), missing)
    }

    @Test fun every_string_is_used_somewhere() {
        val text = kotlinDir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() } +
            File(moduleDir(), "src/main/AndroidManifest.xml").readText() +
            res.walkTopDown().filter { it.extension == "xml" && !it.name.startsWith("strings") }.joinToString("\n") { it.readText() }
        val unused = (english.keys - "app_name").filter { "R.string.$it" !in text && "@string/$it" !in text }
        assertEquals("strings nobody uses (delete them)", emptyList<String>(), unused)
    }

    /**
     * A heuristic, not a parser: it catches the usual ways of putting English straight into
     * a screen - Text("..."), a button label, a content description - in the ui package.
     */
    @Test fun screens_have_no_hard_coded_text() {
        val call = Regex(
            """(?:\b(?:Text|PrimaryButton|SecondaryButton|SmallButton|ScreenHeader|SectionCard|StatusRow)\(\s*(?:\w+\s*=\s*)?""" +
                """|\b(?:contentDescription|title|subtitle|text|label|placeholder|supportingText|description)\s*=\s*)"([^"\n]*)"""",
        )
        val offenders = mutableListOf<String>()
        File(kotlinDir, "com/intercom/app/ui").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            for (m in call.findAll(src)) {
                val t = m.groupValues[1]
                // Animation labels and similar identifiers are lower-case words, not prose.
                if (Regex("[A-Za-z]{3}").containsMatchIn(t) && !Regex("[a-z_]+").matches(t)) {
                    offenders += "${f.name}:${src.substring(0, m.range.first).count { it == '\n' } + 1}  \"$t\""
                }
            }
        }
        assertTrue("hard-coded user-facing text:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun hindi_is_actually_hindi() {
        // Catches a copy-paste of the English text into the Hindi file.
        val untranslated = hindi.filter { (k, v) -> v == english[k] && v.any { it.isLetter() } && v.none { it.code in 0x0900..0x097F } }
            .keys.filterNot { it in ALLOWED_SAME }
        assertEquals("Hindi text identical to English", emptyList<String>(), untranslated)
    }

    /** Brand names, example words and technical labels that stay the same in both languages. */
    private val ALLOWED_SAME = setOf(
        "app_name_header", "code_example", "oem_step_line", "hfp_device_value", "hfp_device_name_or_address",
        "set_hz", "set_db", "transport_wifi", "call_rtt_ms", "sos_label",
    )
}
