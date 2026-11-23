package com.intercom.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class FrameCipherTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private fun pair() = FrameCipher(key, 0) to FrameCipher(key, 1)

    private val audio: Byte = 0x01
    private val floor: Byte = 0x03

    @Test fun roundTripBothWays() {
        val (a, b) = pair()
        val msg = ByteArray(60) { it.toByte() }
        val s = a.seal(audio, 123, 1, msg)
        assertArrayEquals(msg, b.open(audio, 123, s.flags, s.payload))
        val t = b.seal(floor, 4, 0, msg)
        assertArrayEquals(msg, a.open(floor, 4, t.flags, t.payload))
    }

    @Test fun sealedFrameIsMarkedAndKeepsTheCodecBit() {
        val (a, _) = pair()
        val s = a.seal(audio, 0, 1, ByteArray(10))
        assertTrue(FrameCipher.isEncrypted(s.flags))
        assertEquals(1.toByte(), FrameCipher.clearEncryptedBit(s.flags))
    }

    @Test fun overheadIsTwentyBytes() {
        val (a, _) = pair()
        assertEquals(70, a.seal(audio, 0, 0, ByteArray(50)).payload.size)
        assertEquals(20, a.seal(audio, 0, 0, ByteArray(0)).payload.size)
    }

    @Test fun ciphertextIsNotThePlaintext() {
        val (a, _) = pair()
        val msg = ByteArray(40) { 0x55 }
        val s = a.seal(audio, 0, 0, msg)
        val body = s.payload.copyOfRange(4, 4 + 40)
        assertFalse(body.contentEquals(msg))
    }

    // ---- tampering ----------------------------------------------------------------

    @Test fun flippedCiphertextBitIsRejected() {
        val (a, b) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30) { 3 })
        for (i in listOf(4, 10, s.payload.size - 1)) { // body, body, tag
            val bad = s.payload.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", b.open(audio, 1, s.flags, bad))
        }
        assertNotNull("untouched copy still opens", b.open(audio, 1, s.flags, s.payload))
    }

    @Test fun flippedCounterIsRejected() {
        val (a, b) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30))
        val bad = s.payload.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(b.open(audio, 1, s.flags, bad))
    }

    @Test fun tamperedHeaderFieldsAreRejected() {
        val (a, b) = pair()
        val s = a.seal(audio, 100, 1, ByteArray(30))
        assertNull("type", b.open(floor, 100, s.flags, s.payload))
        assertNull("seq", b.open(audio, 101, s.flags, s.payload))
        assertNull("flags (codec bit)", b.open(audio, 100, (s.flags.toInt() xor 1).toByte(), s.payload))
        assertNotNull("original header", b.open(audio, 100, s.flags, s.payload))
    }

    @Test fun truncatedPayloadIsRejected() {
        val (a, b) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30))
        assertNull(b.open(audio, 1, s.flags, s.payload.copyOf(s.payload.size - 1)))
        assertNull(b.open(audio, 1, s.flags, ByteArray(5)))
        assertNull(b.open(audio, 1, s.flags, ByteArray(0)))
    }

    @Test fun wrongKeyIsRejected() {
        val (a, _) = pair()
        val other = FrameCipher(ByteArray(32) { 9 }, 1)
        val s = a.seal(audio, 1, 0, ByteArray(30))
        assertNull(other.open(audio, 1, s.flags, s.payload))
    }

    @Test fun aFrameFromOurOwnDirectionIsRejected() {
        // Reflecting a phone's own frame back at it must fail: the direction is in the nonce.
        val (a, _) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30))
        assertNull(a.open(audio, 1, s.flags, s.payload))
    }

    @Test fun cleartextFrameIsRefusedByOpen() {
        val (_, b) = pair()
        assertNull(b.open(audio, 1, 0, ByteArray(40)))
    }

    @Test fun failedAttemptsDoNotBurnCounters() {
        val (a, b) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30))
        val bad = s.payload.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertNull(b.open(audio, 1, s.flags, bad))
        assertNotNull("the genuine frame with the same counter still opens", b.open(audio, 1, s.flags, s.payload))
    }

    // ---- replay ------------------------------------------------------------------

    @Test fun replayedFrameIsRejected() {
        val (a, b) = pair()
        val s = a.seal(audio, 1, 0, ByteArray(30))
        assertNotNull(b.open(audio, 1, s.flags, s.payload))
        assertNull(b.open(audio, 1, s.flags, s.payload))
        assertEquals(1L, b.rejected)
    }

    @Test fun mildReorderingIsAcceptedOnce() {
        val (a, b) = pair()
        val frames = (0 until 5).map { a.seal(audio, it, 0, ByteArray(10) { _ -> it.toByte() }) }
        for (i in listOf(0, 2, 1, 4, 3)) {
            assertNotNull("frame $i", b.open(audio, i, frames[i].flags, frames[i].payload))
        }
        for (i in 0 until 5) assertNull(b.open(audio, i, frames[i].flags, frames[i].payload))
    }

    @Test fun veryOldFrameFallsOutOfTheWindow() {
        val (a, b) = pair()
        val first = a.seal(audio, 0, 0, ByteArray(4))
        repeat(200) { val s = a.seal(audio, it + 1, 0, ByteArray(4)); b.open(audio, it + 1, s.flags, s.payload) }
        assertNull(b.open(audio, 0, first.flags, first.payload))
    }

    // ---- nonce uniqueness ----------------------------------------------------------

    @Test fun nonceIsUniqueAcrossALongRunBothDirectionsAndSeqWraps() {
        val (a, b) = pair()
        val seen = HashSet<String>(240_000)
        val total = 60_000
        for (i in 0 until total) {
            // The 16-bit header seq wraps every 65,536 frames. The nonce must not.
            val seq = i and 0xFFFF
            val sa = a.seal(audio, seq, 0, ByteArray(8))
            val sb = b.seal(audio, seq, 0, ByteArray(8))
            val ca = counterOf(sa.payload)
            val cb = counterOf(sb.payload)
            assertEquals(i.toLong(), ca)
            assertEquals(i.toLong(), cb)
            assertTrue("a nonce repeated at $i", seen.add(FrameCipher.nonce(0, ca).hex()))
            assertTrue("b nonce repeated at $i", seen.add(FrameCipher.nonce(1, cb).hex()))
        }
        assertEquals(total * 2, seen.size)
    }

    @Test fun seqWrapDoesNotReuseCiphertext() {
        val (a, _) = pair()
        val first = a.seal(audio, 7, 0, ByteArray(20))
        val wrapped = a.seal(audio, 7, 0, ByteArray(20)) // same seq, same plaintext
        assertFalse(first.payload.contentEquals(wrapped.payload))
    }

    @Test fun concurrentSealersNeverShareACounter() {
        val (a, _) = pair()
        val counters = ConcurrentHashMap.newKeySet<Long>()
        val threads = 4
        val each = 4_000
        val go = CountDownLatch(1)
        val workers = (0 until threads).map {
            thread {
                go.await()
                repeat(each) { i -> counters.add(counterOf(a.seal(audio, i, 0, ByteArray(6)).payload)) }
            }
        }
        go.countDown()
        workers.forEach { it.join() }
        assertEquals(threads * each, counters.size)
    }

    @Test fun sealingStopsInsteadOfWrappingTheCounter() {
        val a = FrameCipher(key, 0, startCounter = FrameCipher.MAX_COUNTER)
        assertNotNull(a.seal(audio, 0, 0, ByteArray(4))) // uses 0xFFFFFFFF
        try {
            a.seal(audio, 1, 0, ByteArray(4))
            fail("must not reuse a nonce")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exhausted"))
        }
    }

    @Test fun highCounterValuesRoundTrip() {
        val a = FrameCipher(key, 0, startCounter = 0xFFFFFF00L)
        val b = FrameCipher(key, 1)
        val s = a.seal(audio, 1, 0, ByteArray(12) { 5 })
        assertEquals(0xFFFFFF00L, counterOf(s.payload))
        assertNotNull(b.open(audio, 1, s.flags, s.payload))
    }

    @Test fun oversizePayloadIsRefused() {
        val (a, _) = pair()
        try {
            a.seal(audio, 0, 0, ByteArray(500))
            fail("500 + 20 > 512")
        } catch (e: IllegalArgumentException) { /* expected */ }
        assertNotNull(a.seal(audio, 0, 0, ByteArray(492)))
    }

    private fun counterOf(payload: ByteArray): Long =
        ((payload[0].toLong() and 0xFF) shl 24) or ((payload[1].toLong() and 0xFF) shl 16) or
            ((payload[2].toLong() and 0xFF) shl 8) or (payload[3].toLong() and 0xFF)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
