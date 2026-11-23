package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideLocationTest {

    private class FakeSource : LocationSource {
        var starts = 0
        var stops = 0
        var running = false
        val intervals = ArrayList<Long>()
        var onFix: ((OwnFix) -> Unit)? = null
        var onState: ((LocationState) -> Unit)? = null
        override fun start(intervalMs: Long, onFix: (OwnFix) -> Unit, onState: (LocationState) -> Unit) {
            starts++; running = true; this.onFix = onFix; this.onState = onState
        }
        override fun setInterval(intervalMs: Long) { intervals += intervalMs }
        override fun stop() { stops++; running = false }
        fun fix(northM: Double = 0.0, acc: Double = 5.0) =
            onFix!!(OwnFix(12.0 + northM / 111_195.0, 77.0, 0.0, null, acc, 0L))
    }

    private var t = 1_000_000L
    private val src = FakeSource()
    private val sent = ArrayList<PositionReport>()
    private val states = ArrayList<LocationState>()
    private val enabledChanges = ArrayList<Boolean>()

    private fun rl(group: Boolean = false) = RideLocation(
        source = src,
        tracker = PositionTracker { t },
        group = group,
        send = { sent += it; true },
        onState = { states += it },
        onEnabledChanged = { enabledChanges += it },
        now = { t },
    )

    private fun report(northM: Double, speedCms: Int = 0) = PositionReport(
        ((12.0 + northM / 111_195.0) * 1e7).toInt(), (77.0 * 1e7).toInt(), speedCms, 0, 5, 0L,
    )

    // ---- the privacy contract: off means off ---------------------------------------------------

    @Test fun nothingIsReadSentOrStoredWhileSharingIsOff() {
        val r = rl()
        r.configure(share = false, permitted = true)
        assertFalse(src.running)
        assertEquals(0, src.starts)
        r.announce()
        assertTrue(sent.isEmpty())
        assertFalse(r.onReport(0, report(100.0), 0))
        assertTrue(r.tracker.snapshot().isEmpty())
        assertTrue(r.distances().isEmpty())
    }

    @Test fun nothingIsStartedWithoutThePermission() {
        val r = rl()
        r.configure(share = true, permitted = false)
        assertEquals(0, src.starts)
        assertFalse(r.enabled)
        assertEquals(LocationState.NoPermission, r.state)
        assertEquals(listOf(LocationState.NoPermission), states)
    }

    @Test fun switchingOnStartsTheSourceOnce() {
        val r = rl()
        r.configure(true, true)
        r.configure(true, true)
        assertEquals(1, src.starts)
        assertTrue(r.enabled)
        assertEquals(listOf(true), enabledChanges)
    }

    @Test fun switchingOffStopsTheSourceAndForgetsEveryone() {
        val r = rl()
        r.configure(true, true)
        src.fix()
        r.onReport(0, report(100.0), 0)
        assertEquals(1, r.distances().size)

        r.configure(false, true)
        assertEquals(1, src.stops)
        assertFalse(src.running)
        assertTrue(r.tracker.snapshot().isEmpty())
        assertNull(r.tracker.own())
        assertEquals(LocationState.Off, r.state)
        assertEquals(listOf(true, false), enabledChanges)
    }

    @Test fun stoppingTheCallStopsTheSource() {
        val r = rl()
        r.configure(true, true)
        r.stop()
        r.stop()                                   // twice is fine
        assertEquals(1, src.stops)
        assertTrue(r.tracker.snapshot().isEmpty())
    }

    @Test fun losingThePermissionMidCallStopsEverything() {
        val r = rl()
        r.configure(true, true)
        r.configure(true, false)
        assertEquals(1, src.stops)
        assertEquals(LocationState.NoPermission, r.state)
        assertFalse(r.enabled)
    }

    // ---- sending and receiving -------------------------------------------------------------------

    @Test fun aFixIsSharedAndAReceivedPositionBecomesADistance() {
        val r = rl()
        r.configure(true, true)
        src.fix()
        assertEquals(1, sent.size)
        assertTrue(r.onReport(PositionTracker.BUDDY, report(240.0), 0))
        assertEquals(240.0, r.distances().getValue(PositionTracker.BUDDY).meters, 1.0)
    }

    @Test fun theGpsRateFollowsThePolicy() {
        val r = rl()
        r.configure(true, true)
        src.fix()
        r.onReport(0, report(900.0), 0)
        t += 1_000; src.fix()
        assertEquals(PositionPolicy.FAST_MS, src.intervals.last())
    }

    @Test fun announceSendsAtOnceOnlyWhenEnabled() {
        val r = rl()
        r.announce()
        assertTrue(sent.isEmpty())
        r.configure(true, true)
        src.fix()
        t += 10
        r.announce()
        assertEquals(2, sent.size)
    }

    @Test fun stateChangesAreReportedOnce() {
        val r = rl()
        r.configure(true, true)
        src.onState!!(LocationState.Searching)
        src.onState!!(LocationState.Searching)
        src.onState!!(LocationState.Active)
        assertEquals(listOf(LocationState.Searching, LocationState.Active), states)
    }

    @Test fun aLateStateFromTheSourceAfterShutdownIsIgnored() {
        val r = rl()
        r.configure(true, true)
        val late = src.onState!!
        r.configure(false, true)
        late(LocationState.Active)
        assertEquals(LocationState.Off, r.state)
    }

    // ---- falling behind -------------------------------------------------------------------------------

    private fun rider(m: Double, stale: Boolean = false) =
        RiderDistance(m, 10.0, 0.0, null, Side.Unknown, 0.0, if (stale) 30L else 1L, stale, false)

    @Test fun theAlertFiresAfterTheHoldAndNotBefore() {
        val r = rl()
        r.configure(true, true)
        assertFalse(r.checkFallingBehind(0, listOf(rider(800.0)), true, 500.0, 20_000))
        assertFalse(r.checkFallingBehind(19_999, listOf(rider(800.0)), true, 500.0, 20_000))
        assertFalse(r.fallingBehind)
        assertTrue(r.checkFallingBehind(20_000, listOf(rider(800.0)), true, 500.0, 20_000))
        assertTrue(r.fallingBehind)
    }

    @Test fun theFarthestCurrentRiderDecides() {
        val r = rl()
        r.configure(true, true)
        val riders = listOf(rider(100.0), rider(900.0), rider(5_000.0, stale = true))
        r.checkFallingBehind(0, riders, true, 500.0, 5_000)
        assertTrue(r.checkFallingBehind(5_000, riders, true, 500.0, 5_000))
    }

    @Test fun aRiderWeHaveLostTrackOfDoesNotRaiseIt() {
        val r = rl()
        r.configure(true, true)
        val gone = listOf(rider(5_000.0, stale = true))
        assertFalse(r.checkFallingBehind(0, gone, true, 500.0, 0))
        assertFalse(r.checkFallingBehind(100_000, gone, true, 500.0, 0))
    }

    @Test fun aDisabledAlertNeverFiresAndClearsTheState() {
        val r = rl()
        r.configure(true, true)
        r.checkFallingBehind(0, listOf(rider(800.0)), true, 500.0, 0)
        assertTrue(r.fallingBehind)
        assertFalse(r.checkFallingBehind(1_000, listOf(rider(800.0)), false, 500.0, 0))
        assertFalse(r.fallingBehind)
    }

    @Test fun noAlertWhileSharingIsOff() {
        val r = rl()
        assertFalse(r.checkFallingBehind(0, listOf(rider(9_000.0)), true, 500.0, 0))
        assertFalse(r.fallingBehind)
    }
}
