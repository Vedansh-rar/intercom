package com.intercom.app

import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.FrameDecoder
import com.intercom.app.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The jitter buffer holds payloads and asks the codec to decode and conceal at playout. */
class JitterBufferCodecTest {

    private val n = AudioConstants.FRAME_SAMPLES

    /** Payload byte 0 is the level the decoded frame is filled with. */
    private class FakeDecoder : FrameDecoder {
        var concealFecCalls = 0
        var concealPlcCalls = 0
        var canConceal = true
        var rejectLevel = -1

        override fun decode(payload: ByteArray, out: ShortArray): Int {
            if (payload[0].toInt() == rejectLevel) return -1
            out.fill(payload[0].toShort(), 0, AudioConstants.FRAME_SAMPLES)
            return AudioConstants.FRAME_SAMPLES
        }

        override fun conceal(nextPayload: ByteArray?, out: ShortArray): Int {
            if (!canConceal) return 0
            return if (nextPayload != null) {
                concealFecCalls++
                out.fill(777, 0, AudioConstants.FRAME_SAMPLES)
                AudioConstants.FRAME_SAMPLES
            } else {
                concealPlcCalls++
                out.fill(555, 0, AudioConstants.FRAME_SAMPLES)
                AudioConstants.FRAME_SAMPLES
            }
        }
    }

    private fun payload(level: Int) = byteArrayOf(level.toByte(), 0, 0)

    private fun buffer(decoder: FrameDecoder? = FakeDecoder()) =
        JitterBuffer(targetFrames = 1).also { it.decoder = decoder }

    @Test
    fun `payloads are decoded at playout in order`() {
        val jb = buffer()
        jb.putPayload(0, payload(10))
        jb.putPayload(1, payload(11))
        val out = ShortArray(n)
        assertTrue(jb.take(out)); assertEquals(10, out[0].toInt())
        assertTrue(jb.take(out)); assertEquals(11, out[0].toInt())
        assertEquals(2, jb.playedFrames)
    }

    @Test
    fun `payloads that arrive out of order are still played in order`() {
        val jb = JitterBuffer(targetFrames = 2).also { it.decoder = FakeDecoder() }
        jb.putPayload(1, payload(11))
        jb.putPayload(0, payload(10))
        val out = ShortArray(n)
        jb.take(out); assertEquals(10, out[0].toInt())
        jb.take(out); assertEquals(11, out[0].toInt())
    }

    @Test
    fun `a lost frame with its successor queued is recovered from the successor`() {
        val dec = FakeDecoder()
        val jb = buffer(dec)
        jb.putPayload(0, payload(10))
        jb.putPayload(2, payload(12)) // frame 1 is missing
        val out = ShortArray(n)
        jb.take(out); assertEquals(10, out[0].toInt())

        jb.take(out) // the gap
        assertEquals("FEC recovery of the gap", 777, out[0].toInt())
        assertEquals(1, dec.concealFecCalls)
        assertEquals(1L, jb.lostFrames)
        assertEquals(1L, jb.codecConcealed)

        // The successor is then decoded normally, not consumed by the recovery.
        jb.take(out); assertEquals(12, out[0].toInt())
    }

    @Test
    fun `a late frame with nothing queued is concealed by the codec`() {
        val dec = FakeDecoder()
        val jb = buffer(dec)
        jb.putPayload(0, payload(10))
        val out = ShortArray(n)
        jb.take(out)
        jb.putPayload(1, payload(11)) // keeps "expecting audio" true
        jb.take(out); jb.take(out)    // frame 1 played, then nothing arrives
        assertEquals(555, out[0].toInt())
        assertEquals(1, dec.concealPlcCalls)
        assertEquals(1L, jb.underruns)
    }

    @Test
    fun `a codec with no concealment falls back to repeating the last frame`() {
        val dec = FakeDecoder().also { it.canConceal = false }
        val jb = buffer(dec)
        jb.putPayload(0, payload(100))
        jb.putPayload(2, payload(102))
        val out = ShortArray(n)
        jb.take(out)
        jb.take(out) // the gap, no codec concealment available
        assertEquals("repeat at 0.6 gain", 60, out[0].toInt())
        assertEquals(0L, jb.codecConcealed)
    }

    @Test
    fun `concealment hands over to comfort noise after a run of losses`() {
        val dec = FakeDecoder()
        val jb = buffer(dec)
        jb.putPayload(0, payload(10))
        jb.putPayload(1, payload(11))
        val out = ShortArray(n)
        jb.take(out); jb.take(out)
        repeat(JitterBuffer.MAX_CODEC_CONCEAL_IN_A_ROW) { jb.take(out) }
        assertEquals(JitterBuffer.MAX_CODEC_CONCEAL_IN_A_ROW.toLong(), jb.codecConcealed)
        jb.take(out) // one more: the codec is no longer trusted
        assertEquals(JitterBuffer.MAX_CODEC_CONCEAL_IN_A_ROW.toLong(), jb.codecConcealed)
        assertTrue("comfort noise is low level", out.all { kotlin.math.abs(it.toInt()) <= 120 })
    }

    @Test
    fun `an undecodable payload is treated as lost and counted`() {
        val dec = FakeDecoder().also { it.rejectLevel = 99 }
        val jb = buffer(dec)
        jb.putPayload(0, payload(10))
        jb.putPayload(1, payload(99)) // corrupt
        jb.putPayload(2, payload(12))
        val out = ShortArray(n)
        jb.take(out); assertEquals(10, out[0].toInt())
        jb.take(out) // the corrupt one: concealed from frame 2
        assertEquals(777, out[0].toInt())
        assertEquals(1L, jb.undecodable)
        jb.take(out); assertEquals(12, out[0].toInt())
    }

    @Test
    fun `payloads with no decoder are not played as audio`() {
        val jb = buffer(decoder = null)
        jb.putPayload(0, payload(10))
        val out = ShortArray(n) { 5 }
        jb.take(out)
        assertEquals(1L, jb.undecodable)
    }

    @Test
    fun `plain pcm still works alongside payloads`() {
        val jb = buffer()
        jb.put(0, ShortArray(n) { 42 })
        val out = ShortArray(n)
        jb.take(out)
        assertEquals(42, out[0].toInt())
    }
}
