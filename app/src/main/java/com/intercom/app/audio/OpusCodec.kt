package com.intercom.app.audio

import com.intercom.app.protocol.CodecId

/**
 * The part of libopus this app uses, as an interface so everything around it can be
 * tested on the JVM with a stand-in. The real implementation is [NativeOpusEngine],
 * which calls into the C++ [OpusCoder] over JNI.
 */
interface OpusEngine {
    /** Encodes [frameSamples] of [pcm] into [out]; returns bytes, or < 0 on error. */
    fun encode(pcm: ShortArray, frameSamples: Int, out: ByteArray): Int

    /**
     * Decodes one packet. [payload] null with [fec] false conceals a lost packet; [payload]
     * set with [fec] true recovers the *previous* packet from this one's in-band FEC.
     * Returns samples written, or < 0 on error.
     */
    fun decode(payload: ByteArray?, fec: Boolean, out: ShortArray, frameSamples: Int): Int

    fun setBitrate(bitsPerSecond: Int): Boolean

    fun release()
}

/**
 * Opus, 16 kHz mono, 20 ms frames, VBR, in-band FEC on, DTX off, VOIP tuning.
 * Those settings live in the native coder; this class adapts it to [AudioCodec].
 */
class OpusCodec(
    private val engine: OpusEngine,
    initialBitrate: Int = DEFAULT_BITRATE,
) : AudioCodec {

    companion object {
        const val DEFAULT_BITRATE = 24_000
        const val MIN_BITRATE = 8_000
        const val MAX_BITRATE = 32_000
    }

    override val id = CodecId.Opus
    override val wireFlag: Byte = 1

    @Volatile override var bitrate: Int = initialBitrate.coerceIn(MIN_BITRATE, MAX_BITRATE)
        private set

    init { engine.setBitrate(bitrate) }

    override fun encode(pcm: ShortArray, out: ByteArray): Int =
        engine.encode(pcm, AudioConstants.FRAME_SAMPLES, out)

    override fun decode(payload: ByteArray, out: ShortArray): Int {
        if (payload.isEmpty()) return 0
        return engine.decode(payload, fec = false, out = out, frameSamples = AudioConstants.FRAME_SAMPLES)
    }

    override fun conceal(nextPayload: ByteArray?, out: ShortArray): Int =
        if (nextPayload != null && nextPayload.isNotEmpty()) {
            // The next packet is here: recover the missing frame from its FEC data.
            engine.decode(nextPayload, fec = true, out = out, frameSamples = AudioConstants.FRAME_SAMPLES)
        } else {
            engine.decode(null, fec = false, out = out, frameSamples = AudioConstants.FRAME_SAMPLES)
        }

    override fun setBitrate(bitsPerSecond: Int) {
        val clamped = bitsPerSecond.coerceIn(MIN_BITRATE, MAX_BITRATE)
        if (clamped == bitrate) return
        if (engine.setBitrate(clamped)) bitrate = clamped
    }

    override fun release() = engine.release()
}
