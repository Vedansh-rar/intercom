package com.intercom.app

import com.intercom.app.audio.AdpcmAudioCodec
import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.CodecChoice
import com.intercom.app.audio.OboeEngine
import com.intercom.app.audio.OpusCodec
import com.intercom.app.audio.OpusEngine
import com.intercom.app.audio.RestartBackoff
import com.intercom.app.protocol.Capabilities
import com.intercom.app.protocol.CodecId
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.NegotiatedFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * libopus itself cannot run in a JVM unit test, so these exercise everything around it
 * with a stand-in [OpusEngine]: plumbing, flags, bitrate handling, negotiation. A test
 * of the real encoder and decoder needs a phone and is on the device checklist.
 */
class OpusCodecTest {

    /** 8-bit companding: lossy like a real codec, bounded error, easy to reason about. */
    private class CompandingEngine : OpusEngine {
        var bitrate = 0
        var released = false
        val calls = mutableListOf<String>()
        var failBitrate = false

        override fun encode(pcm: ShortArray, frameSamples: Int, out: ByteArray): Int {
            for (i in 0 until frameSamples) out[i] = (pcm[i].toInt() shr 8).toByte()
            calls += "encode($frameSamples)"
            return frameSamples
        }

        override fun decode(payload: ByteArray?, fec: Boolean, out: ShortArray, frameSamples: Int): Int {
            calls += "decode(payload=${payload != null}, fec=$fec, n=$frameSamples)"
            if (payload == null) { out.fill(0, 0, frameSamples); return frameSamples }
            for (i in 0 until frameSamples) out[i] = (payload[i].toInt() shl 8).toShort()
            return frameSamples
        }

        override fun setBitrate(bitsPerSecond: Int): Boolean {
            if (failBitrate) return false
            bitrate = bitsPerSecond
            return true
        }

        override fun release() { released = true }
    }

    private fun tone(amp: Double = 8000.0) = ShortArray(AudioConstants.FRAME_SAMPLES) {
        (sin(2.0 * PI * 440.0 * it / AudioConstants.SAMPLE_RATE) * amp).toInt().toShort()
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun `encode then decode comes back within the codec's tolerance`() {
        val codec = OpusCodec(CompandingEngine())
        val pcm = tone()
        val buf = ByteArray(400)
        val n = codec.encode(pcm, buf)
        assertEquals(AudioConstants.FRAME_SAMPLES, n)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        assertEquals(AudioConstants.FRAME_SAMPLES, codec.decode(buf.copyOf(n), out))
        for (i in pcm.indices) {
            assertTrue("sample $i: ${pcm[i]} vs ${out[i]}", abs(pcm[i] - out[i]) <= 256)
        }
    }

    @Test
    fun `an empty payload is rejected without touching the engine`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        assertEquals(0, codec.decode(ByteArray(0), ShortArray(AudioConstants.FRAME_SAMPLES)))
        assertTrue(engine.calls.none { it.startsWith("decode") })
    }

    // ------------------------------------------------------------ concealment

    @Test
    fun `a gap with the next packet present asks for FEC recovery`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        val next = ByteArray(AudioConstants.FRAME_SAMPLES)
        val n = codec.conceal(next, ShortArray(AudioConstants.FRAME_SAMPLES))
        assertEquals(AudioConstants.FRAME_SAMPLES, n)
        assertEquals("decode(payload=true, fec=true, n=320)", engine.calls.last())
    }

    @Test
    fun `a gap with nothing after it asks for plain concealment`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        codec.conceal(null, ShortArray(AudioConstants.FRAME_SAMPLES))
        assertEquals("decode(payload=false, fec=false, n=320)", engine.calls.last())
    }

    @Test
    fun `adpcm reports no concealment so the jitter buffer uses its own`() {
        assertEquals(0, AdpcmAudioCodec().conceal(null, ShortArray(AudioConstants.FRAME_SAMPLES)))
    }

    // ------------------------------------------------------------ bitrate

    @Test
    fun `default bitrate is 24 kbps and is applied at creation`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        assertEquals(24_000, codec.bitrate)
        assertEquals(24_000, engine.bitrate)
    }

    @Test
    fun `setBitrate clamps to the supported range`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        codec.setBitrate(1_000)
        assertEquals(OpusCodec.MIN_BITRATE, codec.bitrate)
        codec.setBitrate(500_000)
        assertEquals(OpusCodec.MAX_BITRATE, codec.bitrate)
        assertEquals(OpusCodec.MAX_BITRATE, engine.bitrate)
    }

    @Test
    fun `an unchanged bitrate is not sent to the engine again`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        engine.bitrate = -1 // sentinel: a second call would overwrite it
        codec.setBitrate(24_000)
        assertEquals(-1, engine.bitrate)
    }

    @Test
    fun `if the engine refuses a bitrate the codec keeps reporting the old one`() {
        val engine = CompandingEngine()
        val codec = OpusCodec(engine)
        engine.failBitrate = true
        codec.setBitrate(12_000)
        assertEquals(24_000, codec.bitrate)
    }

    @Test
    fun `release frees the engine`() {
        val engine = CompandingEngine()
        OpusCodec(engine).release()
        assertTrue(engine.released)
    }

    // ------------------------------------------------------------ wire flag

    @Test
    fun `adpcm keeps flag 0 and opus uses 1`() {
        assertEquals(0.toByte(), AdpcmAudioCodec().wireFlag)
        assertEquals(1.toByte(), OpusCodec(CompandingEngine()).wireFlag)
    }

    // ------------------------------------------------------------ negotiation

    private fun hello(version: Int, caps: Int) = Hello(version, 16000, 320, 1, 1L, "x", caps)

    @Test
    fun `both sides with opus use opus`() {
        val f = NegotiatedFeatures.negotiate(Capabilities.OPUS, hello(2, Capabilities.OPUS))
        assertEquals(CodecId.Opus, CodecChoice.choose(f.codec, opusAvailable = true))
    }

    @Test
    fun `a v1 peer gets adpcm even when we have opus`() {
        val f = NegotiatedFeatures.negotiate(Capabilities.OPUS, hello(1, 0))
        assertEquals(CodecId.Adpcm, CodecChoice.choose(f.codec, opusAvailable = true))
    }

    @Test
    fun `a v2 peer without opus gets adpcm`() {
        val f = NegotiatedFeatures.negotiate(Capabilities.OPUS, hello(2, 0))
        assertEquals(CodecId.Adpcm, CodecChoice.choose(f.codec, opusAvailable = true))
    }

    @Test
    fun `negotiated opus still falls back to adpcm if libopus is unavailable here`() {
        assertEquals(CodecId.Adpcm, CodecChoice.choose(CodecId.Opus, opusAvailable = false))
    }

    // ------------------------------------------------------------ restart backoff

    @Test
    fun `restart backoff grows then plateaus then gives up`() {
        val b = RestartBackoff(longArrayOf(100, 200, 400), maxAttempts = 5)
        assertEquals(listOf(100L, 200L, 400L, 400L, 400L), List(5) { b.next() })
        assertNull(b.next())
        assertEquals(5, b.attemptsMade)
    }

    @Test
    fun `a healthy stream resets the backoff`() {
        val b = RestartBackoff(longArrayOf(100, 200), maxAttempts = 3)
        b.next(); b.next()
        b.reset()
        assertEquals(100L, b.next())
    }

    @Test
    fun `default backoff gives a headset a few seconds then stops`() {
        val b = RestartBackoff()
        var total = 0L
        var steps = 0
        while (true) { total += b.next() ?: break; steps++ }
        assertEquals(8, steps)
        assertTrue("total wait $total ms", total in 10_000..30_000)
    }
}
