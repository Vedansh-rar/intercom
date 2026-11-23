package com.intercom.app.group

import com.intercom.app.audio.AudioCodec
import com.intercom.app.protocol.CodecId

/**
 * A decoder that makes the first payload byte, times 100, a constant frame. Lets a test
 * say exactly what each rider "sounds" like and check it arrives in the mix.
 */
class FakeCodec(val flag: Int) : AudioCodec {
    override val id = if (flag == 1) CodecId.Opus else CodecId.Adpcm
    override val wireFlag: Byte = flag.toByte()
    override val bitrate = 0
    var released = false
    var decoded = 0
    val firstBytes = mutableListOf<Byte>()

    override fun encode(pcm: ShortArray, out: ByteArray): Int = 0

    override fun decode(payload: ByteArray, out: ShortArray): Int {
        check(!released) { "decoder used after release" }
        decoded++
        firstBytes += payload[0]
        out.fill((payload[0] * 100).toShort())
        return out.size
    }

    override fun conceal(nextPayload: ByteArray?, out: ShortArray): Int = 0
    override fun setBitrate(bitsPerSecond: Int) = Unit
    override fun release() { released = true }
}
