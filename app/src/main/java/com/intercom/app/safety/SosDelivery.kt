package com.intercom.app.safety

import com.intercom.app.protocol.SosAlert

/**
 * Decides when an SOS is (re)sent.
 *
 * The link has no acknowledgement for it, so rather than send once and hope, the alert is
 * sent at [SCHEDULE_MS] offsets from the first moment it could be sent, which covers a lost
 * frame and a buddy whose app was busy. If the link is down when the rider triggers it, the
 * first send waits for the link and keeps waiting up to [GIVE_UP_MS], so an SOS pressed in
 * a dead spot goes out when the link comes back instead of being silently lost.
 */
class SosSender(
    private val scheduleMs: LongArray = SCHEDULE_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
) {
    private var startedAt = -1L
    private var firstSendAt = -1L
    private var sent = 0

    /** How many times it has gone out. */
    val sendCount: Int get() = sent

    val active: Boolean get() = startedAt >= 0 && !finished

    /** All scheduled sends done, or the wait for a link ran out. */
    var finished = false
        private set

    /** True when it ran out of time before the link ever came up. */
    var gaveUp = false
        private set

    fun start(now: Long) {
        startedAt = now
        firstSendAt = -1L
        sent = 0
        finished = false
        gaveUp = false
    }

    /** True when the caller should send the alert now. Call about once a second or faster. */
    fun due(now: Long, linkUp: Boolean): Boolean {
        if (!active) return false
        if (firstSendAt < 0) {
            if (!linkUp) {
                if (now - startedAt > giveUpMs) { finished = true; gaveUp = true }
                return false
            }
            firstSendAt = now
        }
        if (sent >= scheduleMs.size) { finished = true; return false }
        if (!linkUp) return false
        if (now - firstSendAt >= scheduleMs[sent]) {
            sent++
            if (sent >= scheduleMs.size) finished = true
            return true
        }
        return false
    }

    fun cancel() {
        startedAt = -1L
        finished = true
    }

    companion object {
        /** Sent immediately, then again after 2 s and 5 s. */
        val SCHEDULE_MS = longArrayOf(0L, 2_000L, 5_000L)
        const val GIVE_UP_MS = 120_000L
    }
}

/**
 * What to do with an SOS that arrives. The same alert comes in several times (see
 * [SosSender]) and, in a group, by several routes, so each distinct alert is raised once.
 * A second distinct alert inside [MIN_GAP_MS] is ignored: nobody sends two real emergencies
 * three seconds apart, and a faulty or hostile peer must not be able to flood the siren.
 */
class SosReceiver(private val minGapMs: Long = MIN_GAP_MS) {
    private val seen = ArrayDeque<Pair<Int, Long>>()
    private var lastRaisedAt = Long.MIN_VALUE / 2

    /**
     * @param sender who it came from: a stable key for a rider (0 for the single buddy)
     * @return true if this is a new alert that should sound and show
     */
    @Synchronized
    fun accept(sender: Int, alert: SosAlert, nowMs: Long): Boolean {
        val key = sender to alert.timestampMs
        if (key in seen) return false
        // An alert refused for arriving too soon after another is not remembered, so its
        // re-send a few seconds later is still raised.
        if (nowMs - lastRaisedAt < minGapMs) return false
        remember(key)
        lastRaisedAt = nowMs
        return true
    }

    @Synchronized
    fun reset() {
        seen.clear()
        lastRaisedAt = Long.MIN_VALUE / 2
    }

    private fun remember(key: Pair<Int, Long>) {
        seen.addLast(key)
        while (seen.size > REMEMBER) seen.removeFirst()
    }

    companion object {
        const val MIN_GAP_MS = 3_000L
        private const val REMEMBER = 32
    }
}
