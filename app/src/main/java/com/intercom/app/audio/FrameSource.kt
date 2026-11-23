package com.intercom.app.audio

/**
 * Where [VoicePlayer] gets its next 20 ms. A two-rider call hands it the one
 * [JitterBuffer]; a group ride hands it a [com.intercom.app.group.GroupMixer] that sums
 * one buffer per rider. The player does not know the difference.
 */
interface FrameSource {
    /**
     * Fills [out] with the next frame. Returns true when it holds audio to play (real or
     * concealed) and false when there is nothing, in which case the player writes silence.
     * Called from the playback thread every 20 ms; must not allocate per call.
     */
    fun take(out: ShortArray): Boolean

    /** Forgets everything queued, e.g. after the audio route was lost and rebuilt. */
    fun reset()
}
