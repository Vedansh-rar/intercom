package com.intercom.app.group

/**
 * Body of a group-mode audio envelope: a 16-bit audio sequence, then the codec payload.
 *
 * The envelope's own sequence cannot order audio: one counter numbers everything a rider
 * sends (floor, peer info, audio), so audio frames are not consecutive in it, and a
 * jitter buffer fed gaps would "conceal" frames that were never lost. The audio sequence
 * counts audio frames only.
 */
object GroupAudio {
    const val HEADER_SIZE = 2

    fun pack(audioSeq: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER_SIZE + length)
        out[0] = (audioSeq shr 8).toByte()
        out[1] = audioSeq.toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, length)
        return out
    }

    /** Sequence, or -1 for a body too short to hold one. */
    fun seq(body: ByteArray): Int =
        if (body.size <= HEADER_SIZE) -1 else ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)

    fun payload(body: ByteArray): ByteArray = body.copyOfRange(HEADER_SIZE, body.size)
}
