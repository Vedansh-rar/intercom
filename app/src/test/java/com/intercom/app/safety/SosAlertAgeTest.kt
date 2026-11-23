package com.intercom.app.safety

import com.intercom.app.protocol.SosAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The position age rides in the spare flag bits and must stay readable by older builds. */
class SosAlertAgeTest {
    private fun roundTrip(a: SosAlert) = SosAlert.parse(a.toBytes())!!

    @Test fun ageRoundsUpToFiveSeconds() {
        assertEquals(0, roundTrip(SosAlert(1, 2, 3L, 0)).positionAgeSec)
        assertEquals(5, roundTrip(SosAlert(1, 2, 3L, 1)).positionAgeSec)
        assertEquals(5, roundTrip(SosAlert(1, 2, 3L, 5)).positionAgeSec)
        assertEquals(10, roundTrip(SosAlert(1, 2, 3L, 6)).positionAgeSec)
        assertEquals(60, roundTrip(SosAlert(1, 2, 3L, 60)).positionAgeSec)
    }

    @Test fun ageIsCappedAtTenAndAHalfMinutes() {
        assertEquals(630, roundTrip(SosAlert(1, 2, 3L, 5000)).positionAgeSec)
    }

    @Test fun unstatedAgeStaysUnstated() {
        assertNull(roundTrip(SosAlert(1, 2, 3L, null)).positionAgeSec)
    }

    @Test fun noPositionMeansNoAge() {
        val back = roundTrip(SosAlert(null, null, 3L, 40))
        assertNull(back.latE7)
        assertNull(back.positionAgeSec)
    }

    @Test fun positionAndTimestampSurviveTheAge() {
        val back = roundTrip(SosAlert(285_000_000, -770_000_000, 123456789L, 12))
        assertEquals(285_000_000, back.latE7)
        assertEquals(-770_000_000, back.lonE7)
        assertEquals(123456789L, back.timestampMs)
        assertEquals(15, back.positionAgeSec)
    }

    @Test fun anOlderMessageWithOnlyTheOneBitParsesWithNoAge() {
        val bytes = SosAlert(285_000_000, 770_000_000, 9L).toBytes()
        bytes[0] = 1 // exactly what a build without the age field sends
        val back = SosAlert.parse(bytes)!!
        assertEquals(285_000_000, back.latE7)
        assertNull(back.positionAgeSec)
    }

    @Test fun theMessageStaysSeventeenBytes() {
        assertEquals(SosAlert.SIZE, SosAlert(1, 2, 3L, 30).toBytes().size)
    }
}
