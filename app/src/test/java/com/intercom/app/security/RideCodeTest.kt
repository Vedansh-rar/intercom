package com.intercom.app.security

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideCodeTest {

    private fun valid(input: String): RideCode =
        (RideCode.parse(input) as RideCode.Parsed.Valid).code

    private fun invalid(input: String): String =
        (RideCode.parse(input) as RideCode.Parsed.Invalid).reason

    @Test fun wordListIsExactly256UniqueLowercaseWords() {
        assertEquals(256, WordList.WORDS.size)
        assertEquals(256, WordList.WORDS.toSet().size)
        assertTrue(WordList.WORDS.all { w -> w.all { it in 'a'..'z' } })
    }

    @Test fun generatedCodeParsesBackToItself() {
        repeat(50) {
            val c = RideCode.generate()
            assertEquals(RideCode.DEFAULT_WORDS, (c as RideCode.Words).words.size)
            assertEquals(c, valid(c.display))
        }
    }

    @Test fun generatedCodesDiffer() {
        val rnd = SecureRandom()
        val codes = (1..200).map { RideCode.generate(random = rnd).canonical }.toSet()
        assertTrue("200 five-word codes should not collide", codes.size > 195)
    }

    @Test fun entropyIsEightBitsPerWord() {
        assertEquals(40.0, RideCode.generate(5).entropyBits, 0.001)
        assertEquals(32.0, RideCode.generate(4).entropyBits, 0.001)
        assertEquals(64.0, RideCode.generate(8).entropyBits, 0.001)
    }

    @Test fun numericCodeIsWeakAndSaysSo() {
        val c = RideCode.generateDigits()
        assertTrue(c.isQuickCode)
        assertTrue(c.entropyBits < 21)
        assertEquals(6, c.canonical.length)
    }

    @Test fun parsingIgnoresCaseAndSeparators() {
        val a = valid("Amber anchor APPLE-arrow, aspen")
        val b = valid("amber  anchor apple arrow aspen")
        assertEquals(a, b)
        assertEquals("amber anchor apple arrow aspen", a.canonical)
    }

    @Test fun digitsMayBeGrouped() {
        assertEquals("123456", valid("123 456").canonical)
        assertEquals("123456", valid("123-456").canonical)
        assertEquals("007042", valid("007042").canonical)
    }

    @Test fun rejectsTooFewOrTooManyWords() {
        assertTrue(invalid("amber anchor apple").contains("at least"))
        val nine = List(9) { WordList.WORDS[it] }.joinToString(" ")
        assertTrue(invalid(nine).contains("At most"))
    }

    @Test fun rejectsAWordNotInTheListByName() {
        val reason = invalid("amber anchor apple arrow aspenn")
        assertTrue(reason, reason.contains("aspenn"))
    }

    @Test fun rejectsBadDigitCountAndMixedInput() {
        assertTrue(invalid("12345").contains("6 digits"))
        assertTrue(invalid("1234567").contains("6 digits"))
        assertTrue(RideCode.parse("amber 12 apple arrow").let { it is RideCode.Parsed.Invalid })
        assertTrue(RideCode.parse("   ").let { it is RideCode.Parsed.Invalid })
    }

    @Test fun qrPayloadRoundTrips() {
        val c = RideCode.generate(6)
        val payload = RideCode.toQrPayload(c)
        assertTrue(payload.startsWith("riderintercom://join?code="))
        assertEquals(c, RideCode.fromQrPayload(payload))
        val d = RideCode.generateDigits()
        assertEquals(d, RideCode.fromQrPayload(RideCode.toQrPayload(d)))
    }

    @Test fun qrFromSomeoneElseIsRefused() {
        assertNull(RideCode.fromQrPayload("https://example.com/?code=amber"))
        assertNull(RideCode.fromQrPayload("riderintercom://join?code=notaword+notaword+x+y"))
        assertNull(RideCode.fromQrPayload("riderintercom://join?code=%ZZ"))
    }

    @Test fun toStringNeverShowsTheSecret() {
        val c = RideCode.generate()
        assertFalse(c.toString().contains((c as RideCode.Words).words.first()))
        assertNotEquals(c.toString(), c.canonical)
    }
}
