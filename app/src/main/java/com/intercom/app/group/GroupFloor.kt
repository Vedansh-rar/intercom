package com.intercom.app.group

import com.intercom.app.floor.FloorState

/**
 * Who has the floor in a group ride, from this phone's point of view.
 *
 * The two-rider [com.intercom.app.floor.FloorArbiter] is left exactly as it was: its
 * timestamp tie-break assumes two phones and a clock they roughly share, neither of which
 * holds across a mesh. This is a separate, pure state machine (no clocks of its own, no
 * Android) so the whole policy is testable.
 *
 * Rules:
 *  - **First speaker wins.** While someone holds the floor, others are *ducked* by
 *    [duckFactor], not muted, so a rider who cuts in is still heard quietly and nobody
 *    is silenced by a lost message.
 *  - **Explicit priority.** A speaker with a higher priority (the host) takes the floor
 *    from a lower one at once; equal or lower priority does not.
 *  - **Simultaneous starts** (within [CONCURRENT_MS]) are settled by priority, then by
 *    the lower sender ID (unsigned), which every phone computes identically. No
 *    timestamps are compared across phones.
 *  - **Queueing.** A local speaker who cannot take the floor is *waiting*: they are not
 *    transmitted, and [tick] reports [Event.ChannelFree] when the holder lets go.
 *  - A holder who goes quiet for [HOLDER_STALE_MS], leaves, or exceeds [MAX_HOLD_MS]
 *    (open-mic only) is released.
 *
 * All methods are synchronised: they are called from the mic thread, the receive thread
 * and a timer.
 */
class GroupFloor(
    val selfId: Int,
    val selfPriority: Int,
) {
    class Holder(
        val id: Int,
        val priority: Int,
        val name: String,
        val since: Long,
        var lastAudio: Long,
    )

    enum class LocalResult { Granted, NewlyGranted, Preempted, Waiting }
    enum class RemoteResult { Accepted, Rejected, LocalYielded }

    sealed class Event {
        /** The holder fell silent or exceeded the open-mic limit. */
        class Released(val holderId: Int) : Event()
        /** A local speaker was waiting and the floor is now free. */
        object ChannelFree : Event()
    }

    private var holder: Holder? = null
    private var waiting = false

    @Synchronized fun holderId(): Int? = holder?.id
    @Synchronized fun holderName(): String? = holder?.name
    @Synchronized fun holderPriority(): Int? = holder?.priority
    @Synchronized fun isWaiting(): Boolean = waiting
    @Synchronized fun selfHolds(): Boolean = holder?.id == selfId

    /** Where the floor stands, in the terms the two-rider UI already uses. */
    @Synchronized fun state(): FloorState = when (holder?.id) {
        null -> FloorState.Idle
        selfId -> FloorState.Talking
        else -> FloorState.Muted
    }

    /** Gain for a rider's voice: full for the holder or when nobody holds, ducked otherwise. */
    @Synchronized fun duckFactor(senderId: Int): Float {
        val h = holder ?: return 1f
        return if (h.id == senderId) 1f else DUCK_GAIN
    }

    /**
     * The local rider is speaking now. Called on every voiced frame; cheap once granted.
     * [NewlyGranted] and [Preempted] are the moments to announce the floor to the group.
     */
    @Synchronized
    fun localSpeaking(now: Long, name: String): LocalResult {
        val h = holder
        if (h == null || isStale(h, now)) {
            holder = Holder(selfId, selfPriority, name, now, now)
            waiting = false
            return LocalResult.NewlyGranted
        }
        if (h.id == selfId) {
            h.lastAudio = now
            return LocalResult.Granted
        }
        if (selfPriority > h.priority) {
            holder = Holder(selfId, selfPriority, name, now, now)
            waiting = false
            return LocalResult.Preempted
        }
        waiting = true
        return LocalResult.Waiting
    }

    /** The local rider stopped speaking. True if they held the floor and it is now free. */
    @Synchronized
    fun localStopped(): Boolean {
        waiting = false
        if (holder?.id == selfId) { holder = null; return true }
        return false
    }

    /**
     * Another rider claims the floor, by FLOOR_TAKEN or simply by their audio arriving.
     * [LocalYielded] means this phone was holding it and must stop transmitting.
     */
    @Synchronized
    fun remoteClaim(senderId: Int, priority: Int, name: String, now: Long): RemoteResult {
        if (senderId == selfId) return RemoteResult.Rejected
        val h = holder
        if (h == null || isStale(h, now)) {
            holder = Holder(senderId, priority, name, now, now)
            return RemoteResult.Accepted
        }
        if (h.id == senderId) {
            h.lastAudio = now
            return RemoteResult.Accepted
        }
        val concurrent = now - h.since <= CONCURRENT_MS
        val wins = priority > h.priority ||
            (concurrent && priority == h.priority && Integer.compareUnsigned(senderId, h.id) < 0)
        if (!wins) return RemoteResult.Rejected

        val wasSelf = h.id == selfId
        holder = Holder(senderId, priority, name, now, now)
        return if (wasSelf) RemoteResult.LocalYielded else RemoteResult.Accepted
    }

    /** The holder said they are finished. Null if they were not the holder. */
    @Synchronized
    fun remoteReleased(senderId: Int): Event? {
        val h = holder ?: return null
        if (h.id != senderId) return null
        holder = null
        return afterRelease(senderId)
    }

    /** A rider left the group; if they held the floor it is free. Null otherwise. */
    @Synchronized
    fun peerLeft(senderId: Int): Event? = remoteReleased(senderId)

    /** Housekeeping, about every 100 ms. At most one event per call. */
    @Synchronized
    fun tick(now: Long, pushToTalk: Boolean): Event? {
        val h = holder
        if (h != null) {
            if (h.id != selfId && isStale(h, now)) {
                holder = null
                return afterRelease(h.id)
            }
            if (!pushToTalk && now - h.since > MAX_HOLD_MS) {
                holder = null
                return afterRelease(h.id)
            }
        }
        return null
    }

    private fun afterRelease(id: Int): Event =
        if (waiting) { waiting = false; Event.ChannelFree } else Event.Released(id)

    @Synchronized
    fun reset() {
        holder = null
        waiting = false
    }

    private fun isStale(h: Holder, now: Long) = h.id != selfId && now - h.lastAudio > HOLDER_STALE_MS

    companion object {
        /** A lower-priority rider still talking is this loud: heard, but clearly behind. */
        const val DUCK_GAIN = 0.3f

        /** Claims closer together than this are treated as simultaneous. */
        const val CONCURRENT_MS = 300L

        const val HOLDER_STALE_MS = 2_000L
        const val MAX_HOLD_MS = 30_000L
    }
}
