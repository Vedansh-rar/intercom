package com.intercom.app.location

/**
 * How often to send a position, and when a rider counts as lost.
 *
 * Faster when it matters (riders far apart, or moving quickly: the picture changes
 * fast and a stale number is wrong), slower when it does not (close together and nearly
 * stationary), because every report is radio time and a GPS fix is battery.
 */
object PositionPolicy {
    const val FAST_MS = 1_000L
    const val MEDIUM_MS = 3_000L
    const val SLOW_MS = 5_000L

    /** In a group each report is forwarded by relays, so do not send faster than this. */
    const val GROUP_MIN_MS = 2_000L

    /** Beyond this the riders are "far apart". */
    const val FAR_M = 300.0

    /** Within this they are "close". */
    const val CLOSE_M = 100.0

    /** About 29 km/h: moving fast. */
    const val FAST_MPS = 8.0

    /** About 7 km/h: effectively stationary. */
    const val SLOW_MPS = 2.0

    /** No report from a rider for this long and their distance is shown greyed out. */
    const val STALE_AFTER_MS = 10_000L

    /** A fix less accurate than this (a cell-tower guess, say) is not used at all. */
    const val MAX_USABLE_ACCURACY_M = 200.0

    /**
     * Milliseconds between our reports.
     * @param distanceM latest distance to the nearest known rider, null if none yet
     * @param ownSpeedMps our ground speed
     * @param peerSpeedMps the fastest other rider's, null if unknown
     */
    fun intervalMs(
        distanceM: Double?,
        ownSpeedMps: Double,
        peerSpeedMps: Double?,
        group: Boolean,
    ): Long {
        val fastest = maxOf(ownSpeedMps, peerSpeedMps ?: 0.0)
        val base = when {
            fastest >= FAST_MPS -> FAST_MS
            distanceM == null -> MEDIUM_MS
            distanceM >= FAR_M -> FAST_MS
            distanceM < CLOSE_M && fastest < SLOW_MPS -> SLOW_MS
            else -> MEDIUM_MS
        }
        return if (group) maxOf(base, GROUP_MIN_MS) else base
    }

    /** True if a report is due. The first one (nothing sent yet) is always due. */
    fun due(nowMs: Long, lastSentMs: Long, intervalMs: Long): Boolean =
        lastSentMs <= 0L || nowMs - lastSentMs >= intervalMs

    fun isStale(ageMs: Long): Boolean = ageMs > STALE_AFTER_MS
}

/**
 * "Buddy falling behind": beyond a distance for a number of seconds, not just for one
 * noisy fix. After it fires it repeats at most every [repeatMs] while the rider stays
 * out, and resets only once they are clearly back (inside 90% of the limit), so a rider
 * hovering at the limit does not make it beep and fall silent over and over.
 *
 * A distance that is stale or unknown never raises it: not knowing where someone is is a
 * different problem from them being far away.
 *
 * Called from one thread (the call's ticker); synchronised anyway.
 */
class FallingBehindMonitor(private val repeatMs: Long = DEFAULT_REPEAT_MS) {
    private var beyondSince = -1L
    private var lastAlertAt = -1L

    /** True when the alert should sound now. */
    @Synchronized
    fun update(
        nowMs: Long,
        distanceM: Double?,
        stale: Boolean,
        thresholdM: Double,
        holdMs: Long,
    ): Boolean {
        if (distanceM == null || stale) {
            beyondSince = -1L
            return false
        }
        if (distanceM <= thresholdM * RESET_FRACTION) {
            beyondSince = -1L
            lastAlertAt = -1L
            return false
        }
        if (distanceM <= thresholdM) return false // in the hysteresis band: no change

        if (beyondSince < 0) beyondSince = nowMs
        if (nowMs - beyondSince < holdMs) return false
        if (lastAlertAt >= 0 && nowMs - lastAlertAt < repeatMs) return false
        lastAlertAt = nowMs
        return true
    }

    /** True from the moment the alert fires until the rider is clearly back in range. */
    val alerting: Boolean
        @Synchronized get() = lastAlertAt >= 0

    @Synchronized
    fun reset() {
        beyondSince = -1L
        lastAlertAt = -1L
    }

    companion object {
        const val RESET_FRACTION = 0.9
        const val DEFAULT_REPEAT_MS = 30_000L
    }
}
