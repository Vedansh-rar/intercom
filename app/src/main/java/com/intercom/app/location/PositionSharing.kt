package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides, for each fix from the GPS, whether to send it now, and how fast the GPS should
 * be polled. No Android in here, so the whole policy is unit-tested.
 *
 * It does nothing at all unless it is given fixes, and it is only given fixes while the rider
 * has "Share my location" on; when sharing is off nothing is created.
 *
 * [send] returns false when the report could not go (no link, not encrypted, buddy has no
 * position support); a report that was not sent does not reset the timer, so the next fix
 * tries again.
 *
 * Reports go out on whatever thread the location callback runs on and only ever enqueue
 * (the links' `send` never blocks), so a report cannot hold up an audio frame.
 */
class PositionSharing(
    private val tracker: PositionTracker,
    private val group: Boolean,
    private val send: (PositionReport) -> Boolean,
    private val onInterval: (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastSentAt = AtomicLong(0L)
    private val lastInterval = AtomicLong(0L)

    @Volatile var sent = 0L; private set
    @Volatile var skipped = 0L; private set

    /** A new fix from the GPS. */
    fun onOwnFix(fix: OwnFix) {
        if (!tracker.setOwn(fix)) return

        val interval = currentInterval(fix)
        if (lastInterval.getAndSet(interval) != interval) onInterval(interval)

        val t = now()
        if (!PositionPolicy.due(t, lastSentAt.get(), interval)) { skipped++; return }
        if (send(PositionCodec.toReport(fix))) {
            lastSentAt.set(t)
            sent++
        }
    }

    /**
     * Send our latest fix right away, ignoring the timer: a rider just connected and
     * should not wait up to five seconds to see a distance.
     */
    fun announce() {
        val fix = tracker.own() ?: return
        if (send(PositionCodec.toReport(fix))) {
            lastSentAt.set(now())
            sent++
        }
    }

    private fun currentInterval(fix: OwnFix): Long {
        val riders = tracker.snapshot().values.filter { !it.stale }
        return PositionPolicy.intervalMs(
            distanceM = riders.minOfOrNull { it.meters },
            ownSpeedMps = fix.speedMps,
            peerSpeedMps = riders.maxOfOrNull { it.peerSpeedMps },
            group = group,
        )
    }
}
