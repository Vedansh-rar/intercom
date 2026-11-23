package com.intercom.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GeoTest {

    // ---- haversine against known pairs ----------------------------------------

    @Test fun londonToParisIsAbout343Kilometres() {
        val d = Geo.distanceM(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_557.0, d, 1_000.0)
    }

    @Test fun newYorkToLosAngelesIsAbout3936Kilometres() {
        val d = Geo.distanceM(40.7128, -74.0060, 34.0522, -118.2437)
        assertEquals(3_935_752.0, d, 5_000.0)
    }

    @Test fun bengaluruToDelhiIsAbout1740Kilometres() {
        val d = Geo.distanceM(12.9716, 77.5946, 28.6139, 77.2090)
        assertEquals(1_739_805.0, d, 5_000.0)
    }

    @Test fun oneThousandthOfADegreeOfLatitudeIs111Metres() {
        assertEquals(111.195, Geo.distanceM(12.0, 77.0, 12.001, 77.0), 0.05)
    }

    @Test fun oneDegreeOfLongitudeOnTheEquatorIs111Kilometres() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 0.0, 1.0), 5.0)
    }

    @Test fun longitudeShrinksWithLatitude() {
        val eq = Geo.distanceM(0.0, 0.0, 0.0, 1.0)
        val at60 = Geo.distanceM(60.0, 0.0, 60.0, 1.0)
        assertEquals(0.5, at60 / eq, 0.002)
    }

    @Test fun aRiderTwoHundredFortyMetresEastIsTwoHundredFortyMetres() {
        val lonStep = 240.0 / (111_195.0 * Math.cos(Math.toRadians(12.0)))
        assertEquals(240.0, Geo.distanceM(12.0, 77.0, 12.0, 77.0 + lonStep), 0.5)
    }

    @Test fun samePointIsZero() {
        assertEquals(0.0, Geo.distanceM(12.0, 77.0, 12.0, 77.0), 0.0)
    }

    @Test fun distanceIsSymmetric() {
        val a = Geo.distanceM(51.5, -0.1, 48.8, 2.3)
        val b = Geo.distanceM(48.8, 2.3, 51.5, -0.1)
        assertEquals(a, b, 1e-6)
    }

    @Test fun antipodesAreHalfTheCircumference() {
        assertEquals(20_015_114.0, Geo.distanceM(0.0, 0.0, 0.0, 180.0), 10.0)
    }

    @Test fun polesAndTheDateLineDoNotBreakIt() {
        assertEquals(10_007_557.0, Geo.distanceM(90.0, 0.0, 0.0, 0.0), 10.0)
        // Across the 180 degree meridian the short way is short.
        assertTrue(Geo.distanceM(0.0, 179.9995, 0.0, -179.9995) < 150.0)
    }

    // ---- bearing --------------------------------------------------------------

    @Test fun cardinalBearings() {
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(0.0, 1.0, 0.0, 0.0), 1e-6)
    }

    @Test fun londonToParisBearsSouthEast() {
        assertEquals(148.1, Geo.bearingDeg(51.5074, -0.1278, 48.8566, 2.3522), 0.3)
    }

    @Test fun newYorkToLosAngelesBearsWestSouthWest() {
        assertEquals(273.7, Geo.bearingDeg(40.7128, -74.0060, 34.0522, -118.2437), 0.3)
    }

    @Test fun bearingIsAlwaysInRange() {
        for (lat in -80..80 step 20) for (lon in -170..170 step 40) {
            val b = Geo.bearingDeg(0.0, 0.0, lat.toDouble(), lon.toDouble())
            assertTrue("bearing $b", b >= 0.0 && b < 360.0)
        }
    }

    // ---- angles ----------------------------------------------------------------

    @Test fun normalizeFoldsIntoRange() {
        assertEquals(10.0, Geo.normalize360(370.0), 1e-9)
        assertEquals(350.0, Geo.normalize360(-10.0), 1e-9)
        assertEquals(0.0, Geo.normalize360(720.0), 1e-9)
    }

    @Test fun signedDeltaIsLeftNegativeRightPositive() {
        assertEquals(-90.0, Geo.signedDelta(270.0), 1e-9)
        assertEquals(90.0, Geo.signedDelta(90.0), 1e-9)
        assertEquals(180.0, Geo.signedDelta(180.0), 1e-9)
        assertEquals(-170.0, Geo.signedDelta(190.0), 1e-9)
        assertEquals(10.0, Geo.signedDelta(-350.0), 1e-9)
    }

    @Test fun relativeBearingHandlesTheWrapAtNorth() {
        // Heading 350, buddy due north-east of us at 10: 20 degrees to the right.
        assertEquals(20.0, Geo.relativeBearing(10.0, 350.0), 1e-9)
        // Heading 10, buddy at 350: 20 degrees to the left.
        assertEquals(-20.0, Geo.relativeBearing(350.0, 10.0), 1e-9)
        // Straight behind.
        assertEquals(180.0, Math.abs(Geo.relativeBearing(180.0, 0.0)), 1e-9)
    }

    @Test fun aheadBehindAndBeside() {
        assertEquals(Side.Ahead, Geo.sideOf(0.0))
        assertEquals(Side.Ahead, Geo.sideOf(59.0))
        assertEquals(Side.Ahead, Geo.sideOf(-60.0))
        assertEquals(Side.Beside, Geo.sideOf(90.0))
        assertEquals(Side.Beside, Geo.sideOf(-100.0))
        assertEquals(Side.Behind, Geo.sideOf(150.0))
        assertEquals(Side.Behind, Geo.sideOf(-180.0))
    }

    @Test fun cardinalNames() {
        assertEquals("N", Geo.cardinal(0.0))
        assertEquals("N", Geo.cardinal(359.0))
        assertEquals("NE", Geo.cardinal(45.0))
        assertEquals("E", Geo.cardinal(90.0))
        assertEquals("SE", Geo.cardinal(135.0))
        assertEquals("S", Geo.cardinal(180.0))
        assertEquals("SW", Geo.cardinal(225.0))
        assertEquals("W", Geo.cardinal(270.0))
        assertEquals("NW", Geo.cardinal(315.0))
    }

    @Test fun coordinateValidation() {
        assertTrue(Geo.isValid(0.0, 0.0))
        assertTrue(Geo.isValid(-90.0, 180.0))
        assertFalse(Geo.isValid(90.1, 0.0))
        assertFalse(Geo.isValid(0.0, -180.1))
        assertFalse(Geo.isValid(Double.NaN, 0.0))
        assertFalse(Geo.isValid(0.0, Double.NaN))
    }

    // ---- showing a distance -----------------------------------------------------

    private fun fmt(m: Double, u: DistanceUnit = DistanceUnit.Kilometres, unc: Double = 0.0) =
        DistanceFormat.format(m, u, unc, Locale.US)

    @Test fun metricFormatting() {
        assertEquals("240 m", fmt(240.0).text)
        assertEquals("65 m", fmt(63.0).text)
        assertEquals("990 m", fmt(990.0).text)
        assertEquals("1.0 km", fmt(996.0).text)
        assertEquals("1.3 km", fmt(1_300.0).text)
        assertEquals("9.9 km", fmt(9_900.0).text)
        assertEquals("12 km", fmt(12_345.0).text)
    }

    @Test fun imperialFormatting() {
        assertEquals("820 ft", fmt(250.0, DistanceUnit.Miles).text)
        assertEquals("0.6 mi", fmt(1_000.0, DistanceUnit.Miles).text)
        assertEquals("1.3 mi", fmt(2_090.0, DistanceUnit.Miles).text)
        assertEquals("12 mi", fmt(19_500.0, DistanceUnit.Miles).text)
    }

    @Test fun aDistanceInsideTheFloorIsShownAsLessThan() {
        val f = fmt(12.0)
        assertEquals("< 20 m", f.text)
        assertFalse(f.precise)
        assertEquals("< 70 ft", fmt(12.0, DistanceUnit.Miles).text)
    }

    @Test fun aLargeUncertaintyRaisesTheFloor() {
        // Two fixes each good to about 20 m: combined about 28 m, so 25 m is not a number.
        assertEquals("< 30 m", fmt(25.0, unc = 28.0).text)
        // The same distance with good fixes is.
        assertEquals("25 m", fmt(25.0, unc = 4.0).text)
        assertTrue(fmt(25.0, unc = 4.0).precise)
    }

    @Test fun theFloorBoundaryItself() {
        assertEquals("20 m", fmt(20.0).text)
        assertEquals("< 20 m", fmt(19.9).text)
    }

    @Test fun unitDefaultsFollowTheCountry() {
        assertEquals(DistanceUnit.Miles, DistanceUnit.defaultFor("US"))
        assertEquals(DistanceUnit.Miles, DistanceUnit.defaultFor("gb"))
        assertEquals(DistanceUnit.Kilometres, DistanceUnit.defaultFor("IN"))
        assertEquals(DistanceUnit.Kilometres, DistanceUnit.defaultFor("DE"))
        assertEquals(DistanceUnit.Kilometres, DistanceUnit.defaultFor(null))
    }
}
