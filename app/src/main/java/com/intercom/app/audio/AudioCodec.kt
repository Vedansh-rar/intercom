package com.intercom.app.audio

import com.intercom.app.protocol.CodecId

/**
 * One voice codec, both directions. [CallController] holds one of these for the call,
 * chosen from the capabilities both phones advertised, and the rest of the pipeline
 * never learns which it is.
 *
 * Threading: [encode] is called only from the capture thread and [decode]/[conceal] only
 * from the playback thread, so an implementation may keep separate encoder and decoder
 * state without locking.
 */
interface AudioCodec : FrameDecoder {
    val id: CodecId

    /**
     * Value carried in the frame header's flags byte for audio, so a receiver can tell
     * which codec produced a frame and drop a mismatch instead of decoding garbage.
     * ADPCM is 0, which is exactly what v1 builds already send.
     */
    val wireFlag: Byte

    /** Encodes one 20 ms frame into [out]. Returns bytes written, or <= 0 on failure. */
    fun encode(pcm: ShortArray, out: ByteArray): Int

    /** Asks the encoder for roughly [bitsPerSecond]. Codecs with a fixed rate ignore it. */
    fun setBitrate(bitsPerSecond: Int)

    /** The bitrate in force, or 0 for a fixed-rate codec. */
    val bitrate: Int

    fun release()

    companion object {
        /** Largest encoded frame any codec here produces; sizes the transmit buffer. */
        const val MAX_ENCODED_BYTES = 400
    }
}

/** The existing IMA ADPCM codec behind the [AudioCodec] interface. Unchanged on the wire. */
class AdpcmAudioCodec : AudioCodec {
    private val encoder = AdpcmCodec.Encoder()

    override val id = CodecId.Adpcm
    override val wireFlag: Byte = 0
    override val bitrate: Int = 0

    override fun encode(pcm: ShortArray, out: ByteArray): Int {
        val bytes = encoder.encodeFrame(pcm)
        System.arraycopy(bytes, 0, out, 0, bytes.size)
        return bytes.size
    }

    override fun decode(payload: ByteArray, out: ShortArray): Int =
        AdpcmCodec.decodeFrame(payload, out)

    /** ADPCM has no concealment of its own; the jitter buffer repeats the last frame. */
    override fun conceal(nextPayload: ByteArray?, out: ShortArray): Int = 0

    override fun setBitrate(bitsPerSecond: Int) = Unit

    override fun release() = Unit
}

/** Which codec to use, given the negotiated features and what this phone can run. */
object CodecChoice {
    fun choose(negotiated: CodecId, opusAvailable: Boolean): CodecId =
        if (negotiated == CodecId.Opus && opusAvailable) CodecId.Opus else CodecId.Adpcm
}
