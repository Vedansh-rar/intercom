package com.intercom.app.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTextTest {

    private fun d(
        meters: Double = 240.0, uncertainty: Double = 8.0, bearing: Double = 90.0,
        rel: Double? = 0.0, side: Side = Side.Ahead, age: Long = 1, stale: Boolean = false,
        ownStale: Boolean = false,
    ) = RiderDistance(meters, uncertainty, bearing, rel, side, 0.0, age, stale, ownStale)

    private val km = DistanceUnit.Kilometres
    private val mi = DistanceUnit.Miles

    @Test fun distanceUsesTheUnitAndTheFloor() {
        assertEquals("240 m", DistanceText.distance(d(), km))
        assertEquals("1.3 km", DistanceText.distance(d(meters = 1_300.0), km))
        assertEquals("< 20 m", DistanceText.distance(d(meters = 12.0), km))
        assertEquals("< 30 m", DistanceText.distance(d(meters = 12.0, uncertainty = 25.0), km))
        assertEquals("0.8 mi", DistanceText.distance(d(meters = 1_300.0), mi))
    }

    @Test fun sideWords() {
        assertEquals("ahead", DistanceText.side(d(side = Side.Ahead)))
        assertEquals("behind", DistanceText.side(d(side = Side.Behind, rel = 180.0)))
        assertEquals("to your left", DistanceText.side(d(side = Side.Beside, rel = -90.0)))
        assertEquals("to your right", DistanceText.side(d(side = Side.Beside, rel = 90.0)))
        assertNull(DistanceText.side(d(side = Side.Unknown, rel = null)))
    }

    @Test fun agoReadsNaturally() {
        assertEquals("0 s ago", DistanceText.ago(0))
        assertEquals("59 s ago", DistanceText.ago(59))
        assertEquals("1 min ago", DistanceText.ago(60))
        assertEquals("59 min ago", DistanceText.ago(3_599))
        assertEquals("over an hour ago", DistanceText.ago(3_600))
    }

    @Test fun lastSeenAppearsOnlyOnceTheFixIsAFewSecondsOld() {
        assertNull(DistanceText.lastSeen(d(age = 0)))
        assertNull(DistanceText.lastSeen(d(age = 4)))
        assertEquals("last seen 5 s ago", DistanceText.lastSeen(d(age = 5)))
        assertEquals("last seen 12 s ago", DistanceText.lastSeen(d(age = 12, stale = true)))
    }

    @Test fun lastSeenIsNotBlamedOnTheBuddyWhenOurGpsIsTheProblem() {
        assertNull(DistanceText.lastSeen(d(age = 30, stale = true, ownStale = true)))
    }

    @Test fun detailJoinsWhatItKnows() {
        assertEquals("ahead", DistanceText.detail(d()))
        assertEquals("ahead · last seen 12 s ago", DistanceText.detail(d(age = 12, stale = true)))
    }

    @Test fun withoutAHeadingTheCompassIsUsed() {
        val x = d(rel = null, side = Side.Unknown, bearing = 45.0)
        assertEquals("NE", DistanceText.compass(x))
        assertEquals("to the NE", DistanceText.detail(x))
    }

    @Test fun nothingToAddMeansNull() {
        assertNull(DistanceText.detail(d(side = Side.Unknown, rel = 0.0)))
    }

    @Test fun spokenSaysItAll() {
        assertEquals("Asha, 240 m away, ahead", DistanceText.spoken("Asha", d(), km))
        val tooClose = DistanceText.spoken("Asha", d(meters = 5.0), km)
        assertTrue(tooClose, tooClose.startsWith("Asha, less than 20 m away"))
        assertTrue(DistanceText.spoken(null, d(), km).startsWith("Rider, "))
        assertTrue(DistanceText.spoken("A", d(age = 20, stale = true), km).endsWith("position is out of date"))
        assertTrue(DistanceText.spoken("A", d(ownStale = true, stale = true), km).endsWith("your GPS has no recent fix"))
    }

    @Test fun gpsNotes() {
        assertNull(DistanceText.gpsNote(LocationState.Off, false, false))
        assertNull(DistanceText.gpsNote(LocationState.Active, true, false))
        assertEquals("Looking for a GPS fix…", DistanceText.gpsNote(LocationState.Searching, false, false))
        assertTrue(DistanceText.gpsNote(LocationState.NoPermission, false, false)!!.contains("not allowed"))
        assertTrue(DistanceText.gpsNote(LocationState.Disabled, false, false)!!.contains("switched off"))
        assertTrue(DistanceText.gpsNote(LocationState.Unavailable, false, false)!!.contains("no GPS"))
        assertTrue(DistanceText.gpsNote(LocationState.Active, false, false)!!.contains("Waiting"))
        assertTrue(DistanceText.gpsNote(LocationState.Active, true, true)!!.contains("weak"))
    }
}
