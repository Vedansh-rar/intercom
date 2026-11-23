package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PositionTrackerTest {

    private var t = 1_000_000L
    private val tr = PositionTracker { t }

    private val lat0 = 12.0
    private val lon0 = 77.0
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(12.0))

    private fun own(
        northM: Double = 0.0, eastM: Double = 0.0, speed: Double = 0.0,
        heading: Double? = null, acc: Double = 5.0,
    ) = OwnFix(lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon, speed, heading, acc, 0L)

    private fun report(
        northM: Double, eastM: Double = 0.0, acc: Int = 5, speedCms: Int = 0, headingDeci: Int = 0,
    ) = PositionReport(
        ((lat0 + northM / mPerDegLat) * 1e7).toInt(),
        ((lon0 + eastM / mPerDegLon) * 1e7).toInt(),
        speedCms, headingDeci, acc, 0L,
    )

    private fun d(key: Int = 0) = tr.snapshot()[key]!!

    // ---- distance ---------------------------------------------------------------

    @Test fun nothingToShowWithoutOurOwnFix() {
        assertTrue(tr.onReport(0, report(240.0)))
        assertTrue(tr.snapshot().isEmpty())
        assertNull(tr.distanceOf(0))
    }

    @Test fun distanceAndBearingToARiderNorthOfUs() {
        tr.setOwn(own())
        tr.onReport(0, report(240.0))
        assertEquals(240.0, d().meters, 0.6)
        assertEquals(0.0, d().bearingDeg, 0.3)
    }

    @Test fun theOrderOfArrivalDoesNotMatter() {
        tr.onReport(0, report(240.0))
        tr.setOwn(own())
        assertEquals(240.0, d().meters, 0.6)
    }

    @Test fun combinedAccuracyIsTheRootSumOfSquares() {
        tr.setOwn(own(acc = 3.0))
        tr.onReport(0, report(100.0, acc = 4))
        assertEquals(5.0, d().uncertaintyM, 1e-6)
    }

    @Test fun twoRidersAreKeptApart() {
        tr.setOwn(own())
        tr.onReport(7, report(100.0))
        tr.onReport(9, report(0.0, eastM = 300.0))
        assertEquals(100.0, d(7).meters, 0.6)
        assertEquals(300.0, d(9).meters, 0.6)
        assertEquals(90.0, d(9).bearingDeg, 0.3)
    }

    // ---- rejecting bad data --------------------------------------------------------

    @Test fun anInvalidOrVeryInaccurateReportIsRefused() {
        tr.setOwn(own())
        assertFalse(tr.onReport(0, PositionReport(910_000_000, 0, 0, 0, 5, 0)))
        assertFalse(tr.onReport(0, report(100.0, acc = 201)))
        assertTrue(tr.onReport(0, report(100.0, acc = 200)))
        assertTrue(tr.snapshot().containsKey(0))
    }

    @Test fun aBadReportDoesNotReplaceAGoodOne() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0))
        tr.onReport(0, report(5_000.0, acc = 5_000))
        assertEquals(100.0, d().meters, 0.6)
    }

    @Test fun anInaccurateOwnFixIsIgnored() {
        assertFalse(tr.setOwn(own(acc = 250.0)))
        assertNull(tr.own())
        assertTrue(tr.setOwn(own(acc = 40.0)))
        assertNotNull(tr.own())
    }

    // ---- freshness --------------------------------------------------------------------

    @Test fun aRiderGoesStaleAfterTenSecondsOfSilence() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0))
        t += 9_999; tr.setOwn(own())
        assertFalse(d().stale)
        t += 2; tr.setOwn(own())               // 10.001 s since their report; our fix is fresh
        assertTrue(d().stale)
        assertFalse(d().ownStale)
        assertEquals(10L, d().ageSeconds)
    }

    @Test fun aStaleRiderIsFreshAgainAsSoonAsTheyReport() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0))
        t += 30_000; tr.setOwn(own())
        assertTrue(d().stale)
        tr.onReport(0, report(110.0))
        assertFalse(d().stale)
        assertEquals(0L, d().ageSeconds)
    }

    @Test fun ourOwnGpsGoingQuietIsReportedAsOurs() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0))
        t += 8_000; tr.onReport(0, report(100.0))   // they are fine
        t += 5_000                                   // 13 s since our last fix
        assertTrue(d().ownStale)
        assertTrue(d().stale)
        assertTrue(d().ageSeconds < 10)
    }

    @Test fun aReportThatSatInAQueueLooksAsOldAsItIs() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0), fixAgeMs = 4_000)
        assertEquals(4L, d().ageSeconds)
        assertFalse(d().stale)
        tr.onReport(0, report(100.0), fixAgeMs = 12_000)
        assertTrue(d().stale)
    }

    @Test fun aNegativeFixAgeIsTreatedAsZero() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0), fixAgeMs = -5_000)
        assertEquals(0L, d().ageSeconds)
    }

    // ---- smoothing -----------------------------------------------------------------------

    @Test fun theDistanceSettlesInsteadOfJumping() {
        tr.setOwn(own())
        tr.onReport(0, report(200.0))
        assertEquals(200.0, d().meters, 0.6)
        t += 1_000; tr.onReport(0, report(220.0))
        assertEquals(208.0, d().meters, 0.6)          // 200 + 0.4 * 20
        t += 1_000; tr.onReport(0, report(180.0))
        assertEquals(196.8, d().meters, 0.8)          // 208 + 0.4 * (180 - 208)
    }

    @Test fun aRealJumpIsNotSmoothedAway() {
        tr.setOwn(own())
        tr.onReport(0, report(200.0))
        t += 1_000; tr.onReport(0, report(600.0))
        assertEquals(600.0, d().meters, 0.6)
    }

    @Test fun anOldAverageIsDiscarded() {
        tr.setOwn(own())
        tr.onReport(0, report(200.0))
        t += 16_000
        tr.onReport(0, report(300.0))
        assertEquals(300.0, d().meters, 0.6)
    }

    @Test fun movingOurselvesRefreshesTheDistance() {
        tr.setOwn(own())
        tr.onReport(0, report(200.0))
        t += 1_000; tr.setOwn(own(northM = 50.0))     // we moved 50 m towards them
        assertEquals(180.0, d().meters, 1.0)          // 200 + 0.4 * (150 - 200)
    }

    // ---- direction ------------------------------------------------------------------------

    @Test fun aheadWhenTheyAreInFrontOfUs() {
        tr.setOwn(own(speed = 10.0, heading = 90.0))   // heading east
        tr.onReport(0, report(0.0, eastM = 240.0))
        assertEquals(0.0, d().relativeDeg!!, 0.5)
        assertEquals(Side.Ahead, d().side)
    }

    @Test fun behindWhenTheyAreAstern() {
        tr.setOwn(own(speed = 10.0, heading = 90.0))
        tr.onReport(0, report(0.0, eastM = -240.0))
        assertEquals(Side.Behind, d().side)
        assertEquals(180.0, Math.abs(d().relativeDeg!!), 0.5)
    }

    @Test fun besideAndWhichWay() {
        tr.setOwn(own(speed = 10.0, heading = 90.0))
        tr.onReport(1, report(240.0))                  // north of an east-bound rider: left
        tr.onReport(2, report(-240.0))                 // south: right
        assertEquals(-90.0, d(1).relativeDeg!!, 0.5)
        assertEquals(90.0, d(2).relativeDeg!!, 0.5)
        assertEquals(Side.Beside, d(1).side)
        assertEquals(Side.Beside, d(2).side)
    }

    @Test fun noHeadingMeansNoArrowAndNoAheadBehind() {
        tr.setOwn(own(speed = 0.3, heading = null))
        tr.onReport(0, report(240.0))
        assertNull(d().relativeDeg)
        assertEquals(Side.Unknown, d().side)
        assertEquals(0.0, d().bearingDeg, 0.3)         // the compass bearing is still known
    }

    @Test fun tooCloseToTellWhichSide() {
        tr.setOwn(own(speed = 10.0, heading = 0.0, acc = 8.0))
        tr.onReport(0, report(12.0, acc = 8))
        assertEquals(Side.Unknown, d().side)
    }

    @Test fun peerSpeedIsReported() {
        tr.setOwn(own())
        tr.onReport(0, report(100.0, speedCms = 1_250))
        assertEquals(12.5, d().peerSpeedMps, 1e-9)
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Test fun forgetAndClear() {
        tr.setOwn(own())
        tr.onReport(1, report(100.0)); tr.onReport(2, report(200.0))
        tr.forget(1)
        assertEquals(setOf(2), tr.snapshot().keys)
        tr.clear()
        assertTrue(tr.snapshot().isEmpty())
        assertNull(tr.own())
    }

    // ---- threads ---------------------------------------------------------------------------------

    @Test fun concurrentUpdatesAndReadsDoNotCorruptOrThrow() {
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val real = PositionTracker()          // real clock this time
        repeat(4) { w ->
            pool.execute {
                try {
                    repeat(2_000) { i ->
                        when (w) {
                            0 -> real.setOwn(own(northM = (i % 50).toDouble()))
                            1 -> real.onReport(1, report(100.0 + i % 40))
                            2 -> real.onReport(2, report(300.0 + i % 40))
                            else -> {
                                real.snapshot()
                                if (i % 500 == 0) real.forget(2)
                            }
                        }
                        Unit
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, errors.get())
        val s = real.snapshot()
        assertTrue(s.containsKey(1))
        assertTrue(s.getValue(1).meters in 50.0..200.0)
    }
}
