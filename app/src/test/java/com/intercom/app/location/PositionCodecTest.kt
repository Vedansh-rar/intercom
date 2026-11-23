package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionCodecTest {

    private fun fix(
        lat: Double = 12.9716, lon: Double = 77.5946, speed: Double = 11.0,
        heading: Double? = 273.5, acc: Double = 6.2, stamp: Long = 5_000_000L,
    ) = OwnFix(lat, lon, speed, heading, acc, stamp)

    @Test fun aFixBecomesTheExpectedReport() {
        val r = PositionCodec.toReport(fix())
        assertEquals(129_716_000, r.latE7)
        assertEquals(775_946_000, r.lonE7)
        assertEquals(1_100, r.speedCms)
        assertEquals(2_735, r.headingDeci)
        assertEquals(7, r.accuracyM)            // 6.2 rounds up: never claim better than we have
        assertEquals(5_000L, r.timestampMs)
    }

    @Test fun theReportSurvivesTheWire() {
        val r = PositionCodec.toReport(fix(lat = -33.8688, lon = 151.2093))
        val bytes = r.toBytes()
        assertEquals(PositionReport.SIZE, bytes.size)
        assertEquals(r, PositionReport.parse(bytes))
    }

    @Test fun negativeCoordinatesRoundTrip() {
        val r = PositionReport.parse(PositionCodec.toReport(fix(lat = -89.9999999, lon = -179.9999999)).toBytes())!!
        assertEquals(-89.9999999, PositionCodec.latDeg(r), 1e-7)
        assertEquals(-179.9999999, PositionCodec.lonDeg(r), 1e-7)
    }

    @Test fun precisionIsAboutAMillimetreAndAHalf() {
        val lat = 12.123456789
        val back = PositionCodec.latDeg(PositionCodec.toReport(fix(lat = lat)))
        assertEquals(lat, back, 5e-8)
    }

    @Test fun valuesSaturateInsteadOfWrapping() {
        val r = PositionCodec.toReport(fix(speed = 9_999.0, acc = 1e9))
        assertEquals(0xFFFF, r.speedCms)
        assertEquals(0xFFFF, r.accuracyM)
        assertEquals(0, PositionCodec.toReport(fix(speed = -3.0)).speedCms)
    }

    @Test fun headingWrapsIntoRange() {
        assertEquals(0, PositionCodec.toReport(fix(heading = 360.0)).headingDeci)
        assertEquals(3_599, PositionCodec.toReport(fix(heading = 359.9)).headingDeci)
        assertEquals(0, PositionCodec.toReport(fix(heading = null)).headingDeci)
    }

    @Test fun aSlowRiderSendsNoUsableHeading() {
        val slow = PositionCodec.toReport(fix(speed = 0.5, heading = 90.0))
        assertNull(PositionCodec.headingDeg(slow))
        val fast = PositionCodec.toReport(fix(speed = 5.0, heading = 90.0))
        assertEquals(90.0, PositionCodec.headingDeg(fast)!!, 0.05)
    }

    @Test fun truncatedPayloadIsRefused() {
        val b = PositionCodec.toReport(fix()).toBytes()
        for (n in 0 until b.size) assertNull("len $n", PositionReport.parse(b.copyOf(n)))
        assertNotNull(PositionReport.parse(b))
    }

    @Test fun trailingBytesAreIgnoredSoALaterVersionCanAppend() {
        val b = PositionCodec.toReport(fix()).toBytes() + byteArrayOf(1, 2, 3)
        assertEquals(PositionCodec.toReport(fix()), PositionReport.parse(b))
    }

    @Test fun usabilityChecks() {
        fun r(latE7: Int = 100_000_000, lonE7: Int = 100_000_000, acc: Int = 10) =
            PositionReport(latE7, lonE7, 0, 0, acc, 0)
        assertTrue(PositionCodec.isUsable(r()))
        assertTrue(PositionCodec.isUsable(r(acc = 200)))
        assertFalse(PositionCodec.isUsable(r(acc = 201)))
        assertFalse(PositionCodec.isUsable(r(latE7 = 910_000_000)))
        assertFalse(PositionCodec.isUsable(r(lonE7 = -1_810_000_000)))
    }

    // ---- clocks ----------------------------------------------------------------

    @Test fun ageWithAMeasuredOffset() {
        // The sender stamped 1000 ms on a clock that runs 250 ms ahead of ours.
        val r = PositionReport(0, 0, 0, 0, 5, timestampMs = 1_000L)
        assertEquals(250L, PositionCodec.ageMs(r, clockOffsetMicros = 250_000L, nowMicros = 1_000_000L))
    }

    @Test fun ageWithNoOffset() {
        val r = PositionReport(0, 0, 0, 0, 5, timestampMs = 1_000L)
        assertEquals(500L, PositionCodec.ageMs(r, 0L, 1_500_000L))
    }

    @Test fun anImplausibleAgeIsNullNotTrusted() {
        val r = PositionReport(0, 0, 0, 0, 5, timestampMs = 1_000L)
        // Stamped in our future: an unmeasured offset, or a wrong one.
        assertNull(PositionCodec.ageMs(r, 0L, 500_000L))
        // Hours old: two unrelated clocks.
        assertNull(PositionCodec.ageMs(r, 0L, 9_000_000_000L))
        assertEquals(30_000L, PositionCodec.ageMs(r, 0L, 31_000_000L))
        assertNull(PositionCodec.ageMs(r, 0L, 31_001_000L))
    }

    @Test fun fixStampIsTheTimeOfTheFixNotOfTheCallback() {
        // The callback runs 100 ms after the fix was taken.
        val stamp = PositionCodec.fixStampMicros(
            nanoTimeNow = 10_000_000_000L,
            elapsedNowNanos = 50_000_000_000L,
            fixElapsedNanos = 49_900_000_000L,
        )
        assertEquals(9_900_000L, stamp)
    }

    @Test fun aFixStampedInTheFutureIsTreatedAsNow() {
        val stamp = PositionCodec.fixStampMicros(10_000_000_000L, 50_000_000_000L, 50_100_000_000L)
        assertEquals(10_000_000L, stamp)
    }

    @Test fun reportsAreIdenticalForIdenticalFixes() {
        assertArrayEquals(
            PositionCodec.toReport(fix()).toBytes(),
            PositionCodec.toReport(fix()).toBytes(),
        )
    }

    // ---- from an Android Location ----------------------------------------------------

    private fun loc(speed: Double, bearing: Double?, acc: Double = 6.0) = PositionCodec.fromLocation(
        12.0, 77.0, speed, bearing, acc,
        nanoTimeNow = 10_000_000_000L, elapsedNowNanos = 50_000_000_000L, fixElapsedNanos = 49_000_000_000L,
    )

    @Test fun aMovingFixKeepsItsBearing() {
        val f = loc(speed = 12.0, bearing = 271.5)
        assertEquals(271.5, f.headingDeg!!, 1e-9)
        assertEquals(12.0, f.speedMps, 1e-9)
    }

    @Test fun aSlowFixDropsItsBearing() {
        assertNull(loc(speed = 0.4, bearing = 123.0).headingDeg)
        assertNull(loc(speed = 1.49, bearing = 123.0).headingDeg)
        assertEquals(123.0, loc(speed = 1.5, bearing = 123.0).headingDeg!!, 1e-9)
    }

    @Test fun noBearingMeansNoHeading() {
        assertNull(loc(speed = 20.0, bearing = null).headingDeg)
    }

    @Test fun aBearingIsWrappedIntoRange() {
        assertEquals(10.0, loc(speed = 10.0, bearing = 370.0).headingDeg!!, 1e-9)
        assertEquals(350.0, loc(speed = 10.0, bearing = -10.0).headingDeg!!, 1e-9)
    }

    @Test fun nonsenseNumbersAreCleaned() {
        val f = loc(speed = Double.NaN, bearing = Double.NaN, acc = Double.NaN)
        assertEquals(0.0, f.speedMps, 0.0)
        assertNull(f.headingDeg)
        assertEquals(PositionCodec.UNKNOWN_ACCURACY_M, f.accuracyM, 0.0)
        assertEquals(0.0, loc(speed = -3.0, bearing = null).speedMps, 0.0)
        assertEquals(0.0, loc(speed = 1.0, bearing = null, acc = -5.0).accuracyM, 0.0)
    }

    @Test fun theStampIsWhenTheFixWasTakenNotWhenItArrived() {
        // taken 1 s before "now" on the elapsed clock => 1 s before now on the nanoTime clock
        assertEquals(9_000_000L, loc(speed = 5.0, bearing = null).stampMicros)
    }
}
