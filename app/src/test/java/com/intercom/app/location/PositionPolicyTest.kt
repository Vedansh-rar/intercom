package com.intercom.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionPolicyTest {

    private fun iv(d: Double?, own: Double = 0.0, peer: Double? = null, group: Boolean = false) =
        PositionPolicy.intervalMs(d, own, peer, group)

    @Test fun farApartMeansFast() {
        assertEquals(1_000L, iv(400.0))
        assertEquals(1_000L, iv(5_000.0))
    }

    @Test fun movingFastMeansFastEvenWhenClose() {
        assertEquals(1_000L, iv(50.0, own = 10.0))
        assertEquals(1_000L, iv(50.0, own = 0.0, peer = 12.0))
    }

    @Test fun closeAndSlowMeansSlow() {
        assertEquals(5_000L, iv(50.0))
        assertEquals(5_000L, iv(50.0, own = 1.9, peer = 1.0))
    }

    @Test fun inBetweenIsMedium() {
        assertEquals(3_000L, iv(200.0))
        assertEquals(3_000L, iv(50.0, own = 3.0))   // not stationary, not fast
        assertEquals(3_000L, iv(150.0, own = 1.0))  // slow but not close
    }

    @Test fun noRiderYetIsMediumUnlessMovingFast() {
        assertEquals(3_000L, iv(null))
        assertEquals(1_000L, iv(null, own = 15.0))
    }

    @Test fun boundariesAreWhereTheyAreDocumented() {
        assertEquals(1_000L, iv(PositionPolicy.FAR_M))
        assertEquals(3_000L, iv(PositionPolicy.FAR_M - 0.1))
        assertEquals(3_000L, iv(PositionPolicy.CLOSE_M))
        assertEquals(5_000L, iv(PositionPolicy.CLOSE_M - 0.1))
        assertEquals(1_000L, iv(10.0, own = PositionPolicy.FAST_MPS))
        assertEquals(3_000L, iv(10.0, own = PositionPolicy.SLOW_MPS))
    }

    @Test fun aGroupNeverSendsFasterThanTheFloor() {
        assertEquals(2_000L, iv(400.0, group = true))
        assertEquals(2_000L, iv(null, own = 20.0, group = true))
        assertEquals(3_000L, iv(200.0, group = true))
        assertEquals(5_000L, iv(50.0, group = true))
    }

    @Test fun dueLogic() {
        assertTrue(PositionPolicy.due(10_000, 0, 5_000))        // nothing sent yet
        assertFalse(PositionPolicy.due(10_000, 8_000, 5_000))
        assertFalse(PositionPolicy.due(12_999, 8_000, 5_000))
        assertTrue(PositionPolicy.due(13_000, 8_000, 5_000))
        assertTrue(PositionPolicy.due(20_000, 8_000, 5_000))
    }

    @Test fun stalenessThreshold() {
        assertFalse(PositionPolicy.isStale(0))
        assertFalse(PositionPolicy.isStale(10_000))
        assertTrue(PositionPolicy.isStale(10_001))
    }
}

class FallingBehindMonitorTest {
    private val th = 500.0
    private val hold = 20_000L

    private fun FallingBehindMonitor.at(t: Long, d: Double?, stale: Boolean = false) =
        update(t, d, stale, th, hold)

    @Test fun firesOnlyAfterTheHoldTime() {
        val m = FallingBehindMonitor()
        assertFalse(m.at(0, 600.0))
        assertFalse(m.at(19_999, 600.0))
        assertTrue(m.at(20_000, 600.0))
    }

    @Test fun repeatsNoMoreOftenThanTheRepeatInterval() {
        val m = FallingBehindMonitor(repeatMs = 30_000)
        m.at(0, 600.0)
        assertTrue(m.at(20_000, 600.0))
        assertFalse(m.at(20_001, 600.0))
        assertFalse(m.at(49_999, 600.0))
        assertTrue(m.at(50_000, 600.0))
    }

    @Test fun aBriefExcursionDoesNotFireAndRestartsTheClock() {
        val m = FallingBehindMonitor()
        m.at(0, 600.0)
        assertFalse(m.at(10_000, 400.0))           // back inside: reset
        assertFalse(m.at(11_000, 600.0))           // new excursion starts here
        assertFalse(m.at(30_999, 600.0))
        assertTrue(m.at(31_000, 600.0))
    }

    @Test fun theBandBelowTheLimitKeepsTheClockRunning() {
        val m = FallingBehindMonitor()
        m.at(0, 600.0)
        assertFalse(m.at(10_000, 480.0))           // 90%..100% of the limit: neither
        assertTrue(m.at(20_000, 600.0))            // the clock from t=0 was kept
    }

    @Test fun exactlyAtTheLimitIsNotBeyondIt() {
        val m = FallingBehindMonitor()
        assertFalse(m.at(0, 500.0))
        assertFalse(m.at(60_000, 500.0))
    }

    @Test fun unknownOrStaleDistanceNeverFiresAndResets() {
        val m = FallingBehindMonitor()
        m.at(0, 600.0)
        assertFalse(m.at(10_000, 600.0, stale = true))
        assertFalse(m.at(11_000, 600.0))           // clock restarted at 11 s
        assertFalse(m.at(30_999, 600.0))
        assertTrue(m.at(31_000, 600.0))
        assertFalse(m.at(40_000, null))
    }

    @Test fun comingBackClearsTheRepeatSoTheNextExcursionFiresAfterTheHoldOnly() {
        val m = FallingBehindMonitor(repeatMs = 120_000)
        m.at(0, 600.0)
        assertTrue(m.at(20_000, 600.0))
        m.at(25_000, 100.0)                         // regrouped
        m.at(26_000, 600.0)
        assertTrue(m.at(46_000, 600.0))             // 20 s after, not 120 s after the last one
    }

    @Test fun resetClearsEverything() {
        val m = FallingBehindMonitor()
        m.at(0, 600.0)
        m.reset()
        assertFalse(m.at(20_000, 600.0))
    }

    @Test fun aLongerHoldIsHonoured() {
        val m = FallingBehindMonitor()
        m.update(0, 900.0, false, 500.0, 60_000)
        assertFalse(m.update(59_999, 900.0, false, 500.0, 60_000))
        assertTrue(m.update(60_000, 900.0, false, 500.0, 60_000))
    }
}
