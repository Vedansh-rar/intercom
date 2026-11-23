package com.intercom.app.location

import com.intercom.app.protocol.PositionReport

/**
 * Everything location does during one call, with no Android in it: owns the GPS source, the
 * sharing policy and the falling-behind monitor, and is switched on and off by the rider's
 * setting. The call creates one per call and calls [stop] when the call ends.
 *
 * While not [enabled] nothing is read from the GPS, nothing is sent ([send] is never called)
 * and anything received is refused by [onReport].
 */
class RideLocation(
    private val source: LocationSource,
    val tracker: PositionTracker,
    group: Boolean,
    send: (PositionReport) -> Boolean,
    private val onState: (LocationState) -> Unit,
    private val onEnabledChanged: (Boolean) -> Unit = {},
    now: () -> Long = System::currentTimeMillis,
) {
    private val sharing = PositionSharing(
        tracker = tracker,
        group = group,
        send = send,
        onInterval = { source.setInterval(it) },
        now = now,
    )
    private val monitor = FallingBehindMonitor()

    @Volatile var enabled = false
        private set

    @Volatile var state = LocationState.Off
        private set

    /**
     * Apply the rider's setting. [permitted] is whether precise location is granted right
     * now. Idempotent: called on start and on every settings change.
     */
    @Synchronized
    fun configure(share: Boolean, permitted: Boolean) {
        when {
            !share -> shutDown(LocationState.Off)
            !permitted -> shutDown(LocationState.NoPermission)
            enabled -> Unit
            else -> {
                enabled = true
                onEnabledChanged(true)
                source.start(PositionPolicy.MEDIUM_MS, sharing::onOwnFix, ::publish)
            }
        }
    }

    /** Send our latest fix now (a rider just connected). A no-op when disabled. */
    fun announce() {
        if (enabled) sharing.announce()
    }

    /**
     * A position arrived from [key]. Refused (false) when sharing is off, so a phone that has
     * not opted in never stores anyone's location.
     */
    fun onReport(key: Int, report: PositionReport, fixAgeMs: Long): Boolean =
        enabled && tracker.onReport(key, report, fixAgeMs)

    /** Distances to everyone with a position; empty when sharing is off or we have no fix. */
    fun distances(): Map<Int, RiderDistance> = if (enabled) tracker.snapshot() else emptyMap()

    /**
     * Should the "falling behind" beep sound now? Judged on the farthest rider whose position
     * is current: a rider we have lost track of is a different problem, shown as "last seen".
     */
    fun checkFallingBehind(
        nowMs: Long,
        riders: Collection<RiderDistance>,
        alertOn: Boolean,
        thresholdM: Double,
        holdMs: Long,
    ): Boolean {
        if (!enabled || !alertOn) { monitor.reset(); return false }
        val farthest = riders.filter { !it.stale }.maxByOrNull { it.meters }
        return monitor.update(nowMs, farthest?.meters, farthest == null, thresholdM, holdMs)
    }

    /** True from the moment the alert sounds until the rider is clearly back in range. */
    val fallingBehind: Boolean get() = enabled && monitor.alerting

    @Synchronized
    fun stop() = shutDown(LocationState.Off)

    @Synchronized
    private fun shutDown(next: LocationState) {
        if (enabled) {
            enabled = false
            onEnabledChanged(false)
            source.stop()
        }
        tracker.clear()
        monitor.reset()
        publish(next)
    }

    private fun publish(s: LocationState) {
        // Fixes arriving after a shut-down must not flip the screen back to "searching".
        if (!enabled && s != LocationState.Off && s != LocationState.NoPermission) return
        if (s == state) return
        state = s
        onState(s)
    }
}
