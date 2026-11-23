package com.intercom.app.diagnostics

/**
 * The text of a shared log: a header that says what built it and what is in it, then the
 * redacted lines. Pure, so the layout and what the header leaves out can be tested.
 */
object LogReport {

    data class Facts(
        val appVersion: String,
        val androidRelease: String,
        val sdk: Int,
        val device: String,
        val generatedAtMs: Long,
        /** Non-personal settings only: transport, noise, talk mode and the like. */
        val settings: List<Pair<String, String>>,
    )

    fun build(facts: Facts, rawLog: String, redactor: LogRedactor, maxLines: Int = MAX_LINES): String {
        val lines = rawLog.lines().filter { it.isNotBlank() }
        val kept = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        val body = kept.joinToString("\n") { redactor.redactLine(it) }
        return buildString {
            appendLine("Rider Intercom log")
            appendLine("App: ${facts.appVersion}")
            appendLine("Android: ${facts.androidRelease} (API ${facts.sdk})")
            appendLine("Device: ${facts.device}")
            appendLine("Created: ${facts.generatedAtMs}")
            for ((k, v) in facts.settings) appendLine("$k: $v")
            appendLine("Lines: ${kept.size}" + if (lines.size > kept.size) " (older ${lines.size - kept.size} dropped)" else "")
            appendLine("Positions, ride codes, names and Bluetooth addresses were removed.")
            appendLine("Read it before you send it. Nothing was uploaded.")
            appendLine("----")
            append(body)
            append('\n')
        }
    }

    const val MAX_LINES = 6000
}
