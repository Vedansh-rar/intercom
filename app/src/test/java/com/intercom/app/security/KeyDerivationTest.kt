package com.intercom.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDerivationTest {
    private val code = (RideCode.parse("amber anchor apple arrow aspen") as RideCode.Parsed.Valid).code
    private val other = (RideCode.parse("amber anchor apple arrow atlas") as RideCode.Parsed.Valid).code
    private val n1 = ByteArray(16) { it.toByte() }
    private val n2 = ByteArray(16) { (it + 100).toByte() }

    private fun derive(c: RideCode = code, a: ByteArray = n1, b: ByteArray = n2, sa: Long = 7, sb: Long = 9) =
        KeyDerivation.derive(c, a, b, sa, sb, iterations = 10)

    @Test fun bothPhonesGetTheSameKeysWhicheverOrderTheyPassThings() {
        val a = derive(a = n1, b = n2, sa = 7, sb = 9)
        val b = derive(a = n2, b = n1, sa = 9, sb = 7)
        assertArrayEquals(a.auth, b.auth)
        assertArrayEquals(a.enc, b.enc)
    }

    @Test fun differentCodeGivesDifferentKeys() {
        val a = derive()
        val b = derive(c = other)
        assertFalse(a.auth.contentEquals(b.auth))
        assertFalse(a.enc.contentEquals(b.enc))
    }

    @Test fun everyConnectionGetsFreshKeys() {
        val a = derive()
        val b = derive(b = ByteArray(16) { 55 })
        assertFalse(a.enc.contentEquals(b.enc))
        val c = derive(sb = 10)
        assertFalse(a.enc.contentEquals(c.enc))
    }

    @Test fun authAndEncKeysAreIndependent() {
        val k = derive()
        assertFalse(k.auth.contentEquals(k.enc))
        assertEquals(32, k.auth.size)
        assertEquals(32, k.enc.size)
    }

    @Test fun iterationCountChangesTheKey() {
        val a = KeyDerivation.derive(code, n1, n2, 7, 9, 10)
        val b = KeyDerivation.derive(code, n1, n2, 7, 9, 11)
        assertFalse(a.enc.contentEquals(b.enc))
    }

    @Test fun wipeZeroesTheKeys() {
        val k = derive()
        k.wipe()
        assertTrue(k.auth.all { it == 0.toByte() })
        assertTrue(k.enc.all { it == 0.toByte() })
    }

    @Test fun hkdfExpandMatchesRfc5869TestCase1FirstBlock() {
        // RFC 5869 A.1: PRK, info f0..f9, first 32 bytes of OKM.
        val prk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = KeyDerivation.hkdfExpand(prk, info)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.toHex())
    }

    @Test fun pbkdf2MatchesAKnownVector() {
        // RFC 7914 section 11 vector: PBKDF2-HMAC-SHA256("passwd", "salt", 1, 64) starts 55ac046e...
        // Verifies the platform primitive is the one we think it is.
        val spec = javax.crypto.spec.PBEKeySpec("passwd".toCharArray(), "salt".toByteArray(), 1, 256)
        val out = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", out.toHex())
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
