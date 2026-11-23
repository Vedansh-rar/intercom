package com.intercom.app.group

import com.intercom.app.audio.AudioConstants
import com.intercom.app.protocol.Protocol
import java.util.concurrent.atomic.AtomicLong

/**
 * The relay rules for group mode, with no radio in sight so they can be tested as a
 * network of in-memory nodes.
 *
 * For each envelope that arrives:
 *  1. our own message coming back round a loop is dropped;
 *  2. a message already seen from this sender (by any route) is dropped;
 *  3. audio older than the jitter budget is dropped - playing it late is worse than a gap;
 *  4. otherwise it is delivered here, and, if it has hops left (ttl > 1), a copy with ttl-1
 *     and the extra age is returned for the caller to send to every neighbour except the
 *     one it came from.
 *
 * Only audio is age-limited; floor and peer messages are tiny and still true when late.
 */
class MeshRouter(
    val selfId: Int,
    private val dedup: DedupWindow = DedupWindow(),
) {
    /** What to do with one received envelope. */
    class Decision(
        /** Hand the inner message to this phone's pipeline. */
        val deliver: Boolean,
        /** Forward this to neighbours other than the sender; null when it should not go on. */
        val relay: MeshEnvelope?,
        val dropReason: String? = null,
    )

    private val nextSeq = AtomicLong(0)

    /** Builds a message of ours for the mesh: full ttl, no age, our ID, our next sequence. */
    fun originate(innerType: Byte, innerFlags: Byte, body: ByteArray): MeshEnvelope =
        MeshEnvelope(
            ttl = MeshEnvelope.MAX_TTL,
            senderId = selfId,
            seq = nextSeq.getAndIncrement() and 0xFFFFFFFFL,
            ageMs = 0,
            innerType = innerType,
            innerFlags = innerFlags,
            body = body,
        )

    /**
     * @param residenceMs how long this phone held the envelope before it would forward it
     *   (queueing and decrypt time); zero if the caller has no measure.
     */
    fun onReceive(env: MeshEnvelope, residenceMs: Int = 0): Decision {
        if (env.senderId == selfId) return Decision(false, null, "own message")
        if (!dedup.firstSighting(env.senderId, env.seq)) return Decision(false, null, "duplicate")

        val isAudio = env.innerType == Protocol.TYPE_AUDIO
        if (isAudio && env.ageMs > AUDIO_BUDGET_MS) return Decision(false, null, "audio too old")

        val relay = if (env.ttl > 1) {
            val extra = residenceMs.coerceAtLeast(0) + HOP_ALLOWANCE_MS
            val next = env.relayed(extra)
            // A frame that would arrive already past budget is not worth the airtime.
            if (isAudio && next.ageMs > AUDIO_BUDGET_MS) null else next
        } else null

        return Decision(true, relay)
    }

    fun forget(senderId: Int) = dedup.forget(senderId)

    companion object {
        /** The jitter buffer's ceiling: audio later than this cannot be played in time. */
        const val AUDIO_BUDGET_MS = AudioConstants.JITTER_MAX * AudioConstants.FRAME_MS

        /**
         * What each hop is assumed to add on top of the time a relay measurably held the
         * frame: the radio hop itself. Clocks are not synchronised, so true time of flight
         * cannot be measured; this is an allowance, chosen to be on the cautious side.
         */
        const val HOP_ALLOWANCE_MS = 15
    }
}
