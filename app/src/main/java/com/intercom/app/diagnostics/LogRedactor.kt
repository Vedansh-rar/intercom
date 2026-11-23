package com.intercom.app.diagnostics

/**
 * Takes the personal parts out of a log before the rider shares it.
 *
 * What it removes: positions, anything that looks like a ride code or key, the rider's and
 * buddy's names, and the unique half of Bluetooth addresses. What it cannot know about, it
 * leaves: this is a best effort on top of an app that does not log those things in the first
 * place, not a licence to log them. The rider is told to read the file before sending it.
 */
class LogRedactor(
    /** Names to blank out wherever they appear: the rider's own, the buddy's. */
    names: Collection<String> = emptyList(),
    /** Exact secrets, such as the ride code. Also matched with other separators between words. */
    secrets: Collection<String> = emptyList(),
) {
    private val nameRegexes: List<Regex> = names
        .map { it.trim() }
        .filter { it.length >= MIN_NAME }
        .distinct()
        .map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }

    private val secretRegexes: List<Regex> = secrets
        .map { it.trim() }
        .filter { it.length >= MIN_SECRET }
        .distinct()
        .map { secretRegex(it) }

    fun redact(text: String): String = text.lineSequence().joinToString("\n") { redactLine(it) }

    fun redactLine(line: String): String {
        var s = line
        for (r in secretRegexes) s = r.replace(s, "[ride-code]")
        s = SENSITIVE_KEY.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}[redacted]" }
        s = COORD_PAIR.replace(s, "[position]")
        s = LONG_DECIMAL.replace(s) { m ->
            val v = m.value.toDoubleOrNull()
            if (v != null && v in -180.0..180.0) "[coord]" else m.value
        }
        s = MAC.replace(s) { m -> m.value.substring(0, 8) + ":XX:XX:XX" }
        s = LONG_TOKEN.replace(s, "[token]")
        for (r in nameRegexes) s = r.replace(s, "[name]")
        return s
    }

    private fun secretRegex(secret: String): Regex {
        // "river amber tiger" must also match "river-amber-tiger" and "river.amber.tiger".
        val parts = secret.split(Regex("[\\s,.;:_-]+")).filter { it.isNotEmpty() }
        val body = parts.joinToString("[\\s,.;:_-]*") { Regex.escape(it) }
        return Regex(body, RegexOption.IGNORE_CASE)
    }

    companion object {
        private const val MIN_NAME = 2
        private const val MIN_SECRET = 4

        /** `lat=12.3`, `lonE7: 770000000`, `code=...`, `passphrase "..."`: the value goes. */
        private val SENSITIVE_KEY = Regex(
            "(?i)\\b(lat|lon|lng|latitude|longitude|late7|lone7|position|pos|passphrase|password|" +
                "ride[_ ]?code|code|secret|psk|token|key|nonce|salt|mac)(\\s*[:=]\\s*)" +
                "(\"[^\"]*\"|'[^']*'|[^\\s,;)\\]}]+)"
        )

        /** Two coordinates next to each other, with the separators logs use. */
        private val COORD_PAIR = Regex("-?\\d{1,3}\\.\\d{4,}\\s*[,;/ ]\\s*-?\\d{1,3}\\.\\d{4,}")

        /** A bare decimal with five or more places is far more likely a coordinate than a statistic. */
        private val LONG_DECIMAL = Regex("(?<![\\w.])-?\\d{1,3}\\.\\d{5,}(?![\\w.])")

        private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b")

        /** Hex or base64 blobs of key length. Stats and timestamps are never this long and this mixed. */
        private val LONG_TOKEN = Regex("(?<![\\w+/=])(?=[A-Za-z0-9+/_-]*\\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{28,}={0,2}(?![\\w+/=])")
    }
}
