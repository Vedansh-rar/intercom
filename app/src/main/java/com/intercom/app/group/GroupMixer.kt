package com.intercom.app.group

import com.intercom.app.audio.AudioCodec
import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.FrameSource
import com.intercom.app.audio.JitterBuffer
import com.intercom.app.audio.SoftClip
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive side of a group ride: one jitter buffer and one decoder per rider, summed into
 * the single stream the player pulls.
 *
 * **Why per rider.** Decoders are stateful (Opus carries predictor state from frame to
 * frame; a missing frame is concealed from the previous one). Two riders' frames fed
 * through one decoder would corrupt both, and one jitter buffer would interleave two
 * sequence spaces. So each sender gets its own [JitterBuffer] and its own [AudioCodec],
 * the latter built lazily from the codec flag in the frames.
 *
 * **Threads.** [put] runs on the link's receive thread; [take] on the playback thread.
 * The stream table is a [ConcurrentHashMap]. A decoder is created, used and released only
 * inside [take] (the playback thread), never in [put], so a native Opus decoder is never
 * freed while it is in use. Gain and mute are plain concurrent collections.
 *
 * **CPU bound.** At most [maxStreams] riders may be *active* (audio within
 * [ACTIVE_WINDOW_MS]) at once; a further rider's frames are dropped and counted until a
 * slot frees. Idle streams cost nothing and are evicted, decoder released, after
 * [EVICT_AFTER_MS].
 *
 * **Mixing.** Each stream is scaled by its own gain times the ducking factor from the
 * floor (so a rider who is not the floor holder is quieter, not silenced), ramped across
 * the frame so a change does not click, summed in floating point and passed through
 * [SoftClip].
 *
 * @param newDecoder builds a decoder for an audio codec flag (0 ADPCM, 1 Opus), or null if
 *   this phone cannot decode it. Called on the playback thread only.
 */
class GroupMixer(
    private val newDecoder: (codecFlag: Int) -> AudioCodec?,
    private val maxStreams: Int = MAX_ACTIVE_STREAMS,
    private val jitterTarget: Int = AudioConstants.JITTER_TARGET_BT,
    private val duckFactor: (senderId: Int) -> Float = { 1f },
    private val now: () -> Long = System::currentTimeMillis,
) : FrameSource {

    private class Stream(target: Int) {
        val jitter = JitterBuffer(target)
        @Volatile var codecFlag = -1
        @Volatile var lastPutAt = 0L

        // Touched only on the playback thread.
        var decoder: AudioCodec? = null
        var decoderFlag = -2
        var lastGain = 1f
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    private val gains = ConcurrentHashMap<Int, Float>()
    private val muted: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val overCap = AtomicLong()
    private val mutedDrops = AtomicLong()
    private val badCodec = AtomicLong()

    /** Frames dropped because [maxStreams] riders were already talking. */
    val droppedOverCap: Long get() = overCap.get()
    val droppedMuted: Long get() = mutedDrops.get()
    val droppedUnknownCodec: Long get() = badCodec.get()

    private val acc = FloatArray(AudioConstants.FRAME_SAMPLES)
    private val tmp = ShortArray(AudioConstants.FRAME_SAMPLES)

    /**
     * Queues one received audio frame from [senderId]. [audioSeq] is the 16-bit audio
     * sequence from [GroupAudio]. Returns false if the frame was dropped.
     */
    fun put(senderId: Int, audioSeq: Int, codecFlag: Int, payload: ByteArray): Boolean {
        if (muted.contains(senderId)) { mutedDrops.incrementAndGet(); return false }
        if (codecFlag != 0 && codecFlag != 1) { badCodec.incrementAndGet(); return false }

        val t = now()
        var s = streams[senderId]
        if (s == null) {
            if (activeCount(t, except = senderId) >= maxStreams) {
                overCap.incrementAndGet()
                return false
            }
            s = streams.computeIfAbsent(senderId) { Stream(jitterTarget) }
        } else if (!isActive(s, t) && activeCount(t, except = senderId) >= maxStreams) {
            // A returning talker needs a free slot like a new one; otherwise a long-idle
            // stream would let a fourth voice in through the back door.
            overCap.incrementAndGet()
            return false
        }
        s.codecFlag = codecFlag
        s.lastPutAt = t
        s.jitter.putPayload(audioSeq and 0xFFFF, payload)
        return true
    }

    override fun take(out: ShortArray): Boolean {
        val t = now()
        acc.fill(0f)
        var any = false

        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()

            if (t - s.lastPutAt > EVICT_AFTER_MS && s.jitter.depth == 0) {
                releaseDecoder(s)
                it.remove()
                continue
            }
            if (!isActive(s, t)) continue
            if (muted.contains(id)) continue

            if (!ensureDecoder(s)) continue
            if (!s.jitter.take(tmp)) continue

            val target = (gains[id] ?: 1f) * duckFactor(id)
            val from = s.lastGain
            s.lastGain = target
            val n = AudioConstants.FRAME_SAMPLES
            for (i in 0 until n) {
                // Linear ramp from last frame's gain to this frame's.
                val g = from + (target - from) * (i + 1) / n
                acc[i] += tmp[i] * g
            }
            any = true
        }

        if (!any) return false
        for (i in 0 until AudioConstants.FRAME_SAMPLES) {
            val y = SoftClip.apply(acc[i] / 32768f) * 32768f
            out[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return true
    }

    override fun reset() {
        for (s in streams.values) s.jitter.reset()
    }

    /** A rider left or was removed: forget the stream. The decoder goes on the next [take]. */
    fun remove(senderId: Int) {
        streams[senderId]?.let { it.lastPutAt = 0L; it.jitter.reset() }
    }

    /** Sets a rider's volume, 0..2, remembered even if their stream is recreated. */
    fun setGain(senderId: Int, gain: Float) {
        gains[senderId] = gain.coerceIn(0f, 2f)
    }

    fun gainOf(senderId: Int): Float = gains[senderId] ?: 1f

    /** A muted rider's frames are dropped on arrival, so nothing is decoded for them. */
    fun setMuted(senderId: Int, mute: Boolean) {
        if (mute) muted.add(senderId) else muted.remove(senderId)
    }

    fun isMuted(senderId: Int): Boolean = muted.contains(senderId)

    /** True while this rider's audio is arriving. */
    fun speaking(senderId: Int): Boolean {
        val s = streams[senderId] ?: return false
        return isActive(s, now())
    }

    /** Frames lost / played for this rider, as a percentage; 0 with too little data. */
    fun lossPercent(senderId: Int): Double {
        val j = streams[senderId]?.jitter ?: return 0.0
        return if (j.playedFrames + j.lostFrames < MIN_FRAMES_FOR_LOSS) 0.0 else j.lossPercent()
    }

    /** Frames of audio this rider has had played, 0 if no stream. */
    fun playedFrames(senderId: Int): Long = streams[senderId]?.jitter?.playedFrames ?: 0L

    val streamCount: Int get() = streams.size

    fun activeStreams(): Int = activeCount(now(), except = null)

    /** Releases every decoder. Call after the player has stopped. */
    fun release() {
        for (s in streams.values) releaseDecoder(s)
        streams.clear()
    }

    private fun isActive(s: Stream, t: Long) =
        t - s.lastPutAt <= ACTIVE_WINDOW_MS || s.jitter.depth > 0

    private fun activeCount(t: Long, except: Int?): Int {
        var n = 0
        for ((id, s) in streams) if (id != except && isActive(s, t)) n++
        return n
    }

    /** (Re)creates the decoder if the sender's codec changed. Playback thread only. */
    private fun ensureDecoder(s: Stream): Boolean {
        val want = s.codecFlag
        if (want < 0) return false
        if (s.decoder != null && s.decoderFlag == want) return true
        releaseDecoder(s)
        val d = newDecoder(want)
        if (d == null) { badCodec.incrementAndGet(); return false }
        s.decoder = d
        s.decoderFlag = want
        s.jitter.decoder = d
        return true
    }

    private fun releaseDecoder(s: Stream) {
        s.jitter.decoder = null
        s.decoder?.release()
        s.decoder = null
        s.decoderFlag = -2
    }

    companion object {
        /** The most riders decoded at once; bounds CPU on a mid-range phone. */
        const val MAX_ACTIVE_STREAMS = 3

        /** Audio within this long means the rider is "speaking". */
        const val ACTIVE_WINDOW_MS = 300L

        /** An idle stream is dropped, and its decoder released, after this. */
        const val EVICT_AFTER_MS = 5_000L

        private const val MIN_FRAMES_FOR_LOSS = 25L
    }
}
