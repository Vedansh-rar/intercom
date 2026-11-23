package com.intercom.app.audio

/**
 * Turns a received payload back into PCM at playout time.
 *
 * The jitter buffer holds **payloads**, not PCM, and calls this when a slot comes due.
 * That is what makes loss concealment codec-aware: Opus can regenerate a missing frame
 * from the next packet's in-band FEC, or extrapolate it with its own concealment, which
 * is far better than repeating the previous frame.
 *
 * Implementations run on the playback thread and must not allocate per call.
 */
interface FrameDecoder {
    /** Decodes [payload] into [out]. Returns samples written, or <= 0 if it was invalid. */
    fun decode(payload: ByteArray, out: ShortArray): Int

    /**
     * A frame is missing. If the following frame has already arrived its payload is
     * [nextPayload] and a codec with in-band FEC can recover the missing audio from it;
     * with null the codec should conceal on its own. Returns samples written, or <= 0
     * when this decoder has no concealment, in which case the jitter buffer falls back
     * to its own repeat-then-comfort-noise.
     */
    fun conceal(nextPayload: ByteArray?, out: ShortArray): Int
}
