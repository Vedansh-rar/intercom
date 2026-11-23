package com.intercom.app.security

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * The shared secret for a ride: a passphrase of 4-8 words, or a 6-digit number.
 *
 * The host picks it, the joiners type it in or scan it as a QR code. It is never put on
 * the wire - the handshake in [AuthSession] only ever proves that both sides know it.
 *
 * [canonical] is what gets stretched into a key, so two phones must reduce whatever the
 * rider typed to exactly the same string. Words are lower-cased and joined with one space;
 * digits are joined with nothing.
 */
sealed class RideCode {
    abstract val canonical: String

    /** Rough strength against a guesser who can try every code offline. */
    abstract val entropyBits: Double

    /** What the rider reads out or sees on screen. */
    abstract val display: String

    class Words(val words: List<String>) : RideCode() {
        override val canonical: String get() = words.joinToString(" ")
        override val entropyBits: Double get() = words.size * WordList.BITS_PER_WORD.toDouble()
        override val display: String get() = canonical
    }

    class Digits(val digits: String) : RideCode() {
        override val canonical: String get() = digits
        override val entropyBits: Double get() = digits.length * LOG2_10
        override val display: String get() = digits.chunked(3).joinToString(" ")
    }

    /** True for the short numeric form, which is weak against a recorded handshake. */
    val isQuickCode: Boolean get() = this is Digits

    override fun equals(other: Any?) = other is RideCode && canonical == other.canonical
    override fun hashCode() = canonical.hashCode()

    /** Never print the secret by accident (logs, crash reports). */
    override fun toString() = "RideCode(${if (this is Words) "words" else "digits"}, redacted)"

    /** The outcome of reading what the rider typed. */
    sealed class Parsed {
        data class Valid(val code: RideCode) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    companion object {
        const val MIN_WORDS = 4
        const val MAX_WORDS = 8
        const val DEFAULT_WORDS = 5
        const val DIGITS_LENGTH = 6
        private val LOG2_10 = Math.log(10.0) / Math.log(2.0)

        /** A fresh random passphrase. [random] is injectable so tests are deterministic. */
        fun generate(
            wordCount: Int = DEFAULT_WORDS,
            random: SecureRandom = SecureRandom(),
        ): RideCode {
            require(wordCount in MIN_WORDS..MAX_WORDS) { "wordCount must be $MIN_WORDS..$MAX_WORDS" }
            return Words(List(wordCount) { WordList.WORDS[random.nextInt(WordList.SIZE)] })
        }

        /** A random 6-digit quick code. Leading zeros are kept. */
        fun generateDigits(random: SecureRandom = SecureRandom()): RideCode =
            Digits("%06d".format(random.nextInt(1_000_000)))

        /**
         * Reads what the rider typed. Separators (space, hyphen, comma, dot, underscore) and
         * case do not matter. An unknown word is reported by name so a typo is caught here,
         * at the entry field, rather than as a confusing "wrong ride code" after connecting.
         */
        fun parse(input: String): Parsed {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Parsed.Invalid("Enter the ride code")

            val tokens = trimmed.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }

            // Digits, possibly grouped as "123 456" or "123-456".
            if (tokens.all { t -> t.all { it in '0'..'9' } }) {
                val joined = tokens.joinToString("")
                return if (joined.length == DIGITS_LENGTH) Parsed.Valid(Digits(joined))
                else Parsed.Invalid("A number code has $DIGITS_LENGTH digits")
            }

            if (tokens.any { t -> t.any { it !in 'a'..'z' } }) {
                return Parsed.Invalid("Use words only, or a $DIGITS_LENGTH-digit number")
            }
            if (tokens.size < MIN_WORDS) return Parsed.Invalid("Need at least $MIN_WORDS words")
            if (tokens.size > MAX_WORDS) return Parsed.Invalid("At most $MAX_WORDS words")
            tokens.firstOrNull { !WordList.contains(it) }?.let {
                return Parsed.Invalid("\"$it\" is not one of the ride words")
            }
            return Parsed.Valid(Words(tokens))
        }

        fun parseOrNull(input: String?): RideCode? =
            input?.let { (parse(it) as? Parsed.Valid)?.code }

        // ---- QR payload ---------------------------------------------------------

        private const val QR_PREFIX = "riderintercom://join?code="

        /**
         * What the QR code encodes. It carries the secret, so it is shown only on the
         * host's own screen; it is never sent anywhere.
         */
        fun toQrPayload(code: RideCode): String =
            QR_PREFIX + URLEncoder.encode(code.canonical, "UTF-8")

        /** The code in a scanned QR, or null if it is not one of ours. */
        fun fromQrPayload(payload: String): RideCode? {
            if (!payload.startsWith(QR_PREFIX)) return null
            val raw = runCatching {
                URLDecoder.decode(payload.removePrefix(QR_PREFIX), "UTF-8")
            }.getOrNull() ?: return null
            return parseOrNull(raw)
        }

        private val SEPARATORS = Regex("[\\s,.;:_-]+")
    }
}
