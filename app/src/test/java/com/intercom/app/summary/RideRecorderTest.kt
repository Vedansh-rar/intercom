package com.intercom.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRecorderTest {

    private fun ticks(r: RideRecorder, from: Long, to: Long, connected: Boolean = true, rtt: Int = 50, loss: Double = 0.0, transport: String? = "Bluetooth") {
        var t = from
        while (t <= to) { r.onTick(t, connected, rtt, loss, transport); t += 1000 }
    }

    @Test fun sampleEveryFiveSeconds() {
        val r = RideRecorder()
        r.start(0)
        ticks(r, 0, 20_000)
        val s = r.finish(20_000)!!
        assertEquals(5, s.samples.size)
        assertEquals(listOf(0L, 5000L, 10000L, 15000L, 20000L), s.samples.map { it.atMs })
        assertEquals(20_000L, s.durationMs)
    }

    @Test fun aTransportSwitchIsRecordedOnce() {
        val r = RideRecorder()
        r.start(1000)
        ticks(r, 1000, 10_000, transport = "Bluetooth")
        ticks(r, 11_000, 20_000, transport = "Wi-Fi Direct")
        val s = r.finish(20_000)!!
        assertEquals(1, s.switches.size)
        assertEquals("Bluetooth", s.switches[0].from)
        assertEquals("Wi-Fi Direct", s.switches[0].to)
        assertEquals(10_000L, s.switches[0].atMs)
    }

    @Test fun theFirstTransportIsNotASwitch() {
        val r = RideRecorder()
        r.start(0)
        ticks(r, 0, 5000)
        assertTrue(r.finish(5000)!!.switches.isEmpty())
    }

    @Test fun aDropAndRecoveryCountsOneLossAndItsDuration() {
        val r = RideRecorder()
        r.start(0)
        ticks(r, 0, 9000)
        ticks(r, 10_000, 13_000, connected = false)
        ticks(r, 14_000, 20_000)
        val s = r.finish(20_000)!!
        assertEquals(1, s.linkLosses)
        assertEquals(4000L, s.downMs)
        assertEquals(80, s.connectedPercent)
    }

    @Test fun aLinkStillDownAtTheEndCountsUntilTheEnd() {
        val r = RideRecorder()
        r.start(0)
        ticks(r, 0, 5000)
        ticks(r, 6000, 9000, connected = false)
        val s = r.finish(10_000)!!
        assertEquals(1, s.linkLosses)
        assertEquals(4000L, s.downMs)
    }

    @Test fun averagesIgnoreDisconnectedAndUnmeasuredReadings() {
        val r = RideRecorder(sampleEveryMs = 1000)
        r.start(0)
        r.onTick(0, true, 100, 0.0, "Bluetooth")
        r.onTick(1000, true, 200, 2.0, "Bluetooth")
        r.onTick(2000, true, -1, 0.0, "Bluetooth")
        r.onTick(3000, false, 900, 50.0, "Bluetooth")
        val s = r.finish(4000)!!
        assertEquals(150, s.avgRttMs)
        assertEquals(200, s.worstRttMs)
        assertEquals(2.0, s.worstLossPercent, 0.0)
    }

    @Test fun noMeasurementsMeansNoAverage() {
        val r = RideRecorder()
        r.start(0)
        r.onTick(0, true, -1, 0.0, null)
        val s = r.finish(1000)!!
        assertNull(s.avgRttMs)
        assertNull(s.worstRttMs)
    }

    @Test fun finishingWithoutStartingGivesNothing() {
        assertNull(RideRecorder().finish(5000))
    }

    @Test fun finishingResetsTheRecorder() {
        val r = RideRecorder()
        r.start(0)
        ticks(r, 0, 3000)
        r.finish(3000)
        assertFalse(r.started)
        assertNull(r.finish(4000))
    }

    @Test fun ticksBeforeStartAreIgnored() {
        val r = RideRecorder()
        r.onTick(0, true, 10, 0.0, "Bluetooth")
        r.start(100)
        assertTrue(r.finish(200)!!.samples.isEmpty())
    }

    @Test fun downsamplingKeepsTheWorstReadingOfEachBucket() {
        val src = (0 until 300).map { LinkSample(it * 5000L, connected = it != 150, rttMs = 50, lossPercent = 0.0, transport = "Bluetooth") }
        val out = RideRecorder.downsample(src, 120)
        assertEquals(120, out.size)
        assertTrue(out.any { !it.connected })
        assertEquals(1, out.count { !it.connected })
    }

    @Test fun shortRidesAreNotDownsampled() {
        val src = (0 until 10).map { LinkSample(it * 5000L, true, 50, 0.0, null) }
        assertEquals(src, RideRecorder.downsample(src, 120))
    }

    @Test fun groupRidesRememberTheLargestGroup() {
        val r = RideRecorder()
        r.start(0, group = true)
        r.onTick(0, true, -1, 0.0, null, riders = 3)
        r.onTick(5000, true, -1, 0.0, null, riders = 5)
        r.onTick(10000, true, -1, 0.0, null, riders = 4)
        val s = r.finish(10_000)!!
        assertTrue(s.group)
        assertEquals(5, s.riders)
    }

    @Test fun linkQualityScore() {
        assertEquals(0f, LinkScore.score(false, 10, 0.0), 0f)
        assertEquals(1f, LinkScore.score(true, 50, 0.0), 0.001f)
        assertEquals(1f, LinkScore.score(true, -1, 0.0), 0.001f)
        assertEquals(0.35f, LinkScore.score(true, 50, 20.0), 0.001f)
        assertEquals(0.65f, LinkScore.score(true, 900, 0.0), 0.001f)
        assertEquals(0f, LinkScore.score(true, 900, 80.0), 0.001f)
    }

    @Test fun connectedPercentOfAnEmptyRideIsFull() {
        val r = RideRecorder()
        r.start(0)
        assertEquals(100, r.finish(0)!!.connectedPercent)
    }
}
