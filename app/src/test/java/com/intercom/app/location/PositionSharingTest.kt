package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionSharingTest {

    private var t = 1_000_000L
    private val tracker = PositionTracker { t }
    private val out = ArrayList<PositionReport>()
    private val intervals = ArrayList<Long>()
    private var linkUp = true

    private fun sharing(group: Boolean = false) = PositionSharing(
        tracker, group,
        send = { if (linkUp) { out += it; true } else false },
        onInterval = { intervals += it },
        now = { t },
    )

    private fun fix(
        lat: Double = 12.0, lon: Double = 77.0, speed: Double = 0.0,
        heading: Double? = null, acc: Double = 5.0,
    ) = OwnFix(lat, lon, speed, heading, acc, 123_000_000L)

    private fun buddyAt(metersNorth: Double, speedCms: Int = 0) = PositionReport(
        ((12.0 + metersNorth / 111_195.0) * 1e7).toInt(), (77.0 * 1e7).toInt(), speedCms, 0, 5, 0L,
    )

    @Test fun theFirstFixIsSentImmediately() {
        val s = sharing()
        s.onOwnFix(fix())
        assertEquals(1, out.size)
        assertEquals(1L, s.sent)
    }

    @Test fun reportCarriesTheFix() {
        val s = sharing()
        s.onOwnFix(fix(lat = 12.5, lon = 77.25, speed = 6.5, heading = 271.3, acc = 7.2))
        val r = out.single()
        assertEquals(125_000_000, r.latE7)
        assertEquals(772_500_000, r.lonE7)
        assertEquals(650, r.speedCms)
        assertEquals(2713, r.headingDeci)
        assertEquals(8, r.accuracyM)            // rounded up: never claim better than we have
        assertEquals(123_000L, r.timestampMs)
    }

    @Test fun laterFixesAreThrottledToTheInterval() {
        val s = sharing()
        s.onOwnFix(fix())                        // alone and stationary: 3 s
        t += 1_000; s.onOwnFix(fix())
        t += 1_000; s.onOwnFix(fix())
        assertEquals(1, out.size)
        assertEquals(2L, s.skipped)
        t += 1_000; s.onOwnFix(fix())            // 3 s since the first
        assertEquals(2, out.size)
    }

    @Test fun aFailedSendDoesNotUseUpTheTimer() {
        val s = sharing()
        linkUp = false
        s.onOwnFix(fix())
        assertEquals(0, out.size)
        assertEquals(0L, s.sent)
        linkUp = true
        t += 100; s.onOwnFix(fix())              // retried straight away
        assertEquals(1, out.size)
    }

    @Test fun anUnusableFixIsNeitherSentNorStored() {
        val s = sharing()
        s.onOwnFix(fix(acc = 500.0))
        s.onOwnFix(fix(lat = 95.0))
        assertEquals(0, out.size)
        assertNull(tracker.own())
        assertTrue(intervals.isEmpty())
    }

    @Test fun theGpsRateIsOnlyChangedWhenItChanges() {
        val s = sharing()
        s.onOwnFix(fix())
        assertEquals(listOf(PositionPolicy.MEDIUM_MS), intervals)
        t += 500; s.onOwnFix(fix())
        t += 500; s.onOwnFix(fix())
        assertEquals(1, intervals.size)
        t += 500; s.onOwnFix(fix(speed = 12.0, heading = 0.0))   // now moving fast
        assertEquals(listOf(PositionPolicy.MEDIUM_MS, PositionPolicy.FAST_MS), intervals)
    }

    @Test fun closeAndStationaryRidersSlowDown() {
        val s = sharing()
        s.onOwnFix(fix())
        tracker.onReport(0, buddyAt(30.0))
        t += 1_000; s.onOwnFix(fix())
        assertEquals(PositionPolicy.SLOW_MS, intervals.last())
        t += 5_000; s.onOwnFix(fix())
        assertEquals(2, out.size)                // 5 s apart now
    }

    @Test fun farApartSpeedsUp() {
        val s = sharing()
        s.onOwnFix(fix())
        tracker.onReport(0, buddyAt(800.0))
        t += 1_000; s.onOwnFix(fix())
        assertEquals(PositionPolicy.FAST_MS, intervals.last())
        assertEquals(2, out.size)                // and 1 s later we send again
    }

    @Test fun aFastBuddySpeedsUsUpEvenWhenWeAreStill() {
        val s = sharing()
        s.onOwnFix(fix())
        tracker.onReport(0, buddyAt(30.0, speedCms = 1_500))
        t += 1_000; s.onOwnFix(fix())
        assertEquals(PositionPolicy.FAST_MS, intervals.last())
    }

    @Test fun aSilentBuddyDoesNotKeepUsOnAnOldRate() {
        val s = sharing()
        s.onOwnFix(fix())
        tracker.onReport(0, buddyAt(30.0))
        t += 1_000; s.onOwnFix(fix())
        assertEquals(PositionPolicy.SLOW_MS, intervals.last())
        t += 20_000; s.onOwnFix(fix())           // their report is now stale: ignore it
        assertEquals(PositionPolicy.MEDIUM_MS, intervals.last())
    }

    @Test fun groupsNeverSendFasterThanTwoSeconds() {
        val s = sharing(group = true)
        s.onOwnFix(fix(speed = 15.0, heading = 0.0))
        assertEquals(PositionPolicy.GROUP_MIN_MS, intervals.last())
        t += 1_000; s.onOwnFix(fix(speed = 15.0, heading = 0.0))
        assertEquals(1, out.size)
        t += 1_000; s.onOwnFix(fix(speed = 15.0, heading = 0.0))
        assertEquals(2, out.size)
    }

    @Test fun announceSendsTheLatestFixAtOnce() {
        val s = sharing()
        s.announce()                             // no fix yet: nothing to send
        assertEquals(0, out.size)
        s.onOwnFix(fix(lat = 12.1))
        t += 100; s.announce()
        assertEquals(2, out.size)
        assertEquals(121_000_000, out[1].latE7)
    }

    @Test fun announceRestartsTheTimer() {
        val s = sharing()
        s.onOwnFix(fix())
        t += 2_500; s.announce()
        t += 1_000; s.onOwnFix(fix())            // only 1 s since the announce
        assertEquals(2, out.size)
        t += 2_000; s.onOwnFix(fix())
        assertEquals(3, out.size)
    }

    @Test fun aFailedAnnounceCountsForNothing() {
        val s = sharing()
        s.onOwnFix(fix())
        linkUp = false
        s.announce()
        assertEquals(1L, s.sent)
        assertFalse(out.size > 1)
    }
}
