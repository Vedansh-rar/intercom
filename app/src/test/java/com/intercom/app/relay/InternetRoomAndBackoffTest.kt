package com.intercom.app.relay

import com.intercom.app.security.RideCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetRoomAndBackoffTest {

    private val code = RideCode.parseOrNull("amber anchor apple arrow aspen atlas")!!
    private val other = RideCode.parseOrNull("amber anchor apple arrow aspen autumn")!!

    // ---- room id (iterations kept tiny: the real cost is the point of the real value)

    @Test fun roomIdIsStableAndLooksRight() {
        val a = InternetRoom.roomId(code, 2)
        assertEquals(a, InternetRoom.roomId(code, 2))
        assertTrue(Regex("[0-9a-f]{32}").matches(a))
    }

    @Test fun differentCodesOrCostsGiveDifferentRooms() {
        assertNotEquals(InternetRoom.roomId(code, 2), InternetRoom.roomId(other, 2))
        assertNotEquals(InternetRoom.roomId(code, 2), InternetRoom.roomId(code, 3))
    }

    @Test fun codeFormattingDoesNotChangeTheRoom() {
        val spaced = RideCode.parseOrNull("  AMBER,  anchor apple ARROW aspen atlas ")!!
        assertEquals(InternetRoom.roomId(code, 2), InternetRoom.roomId(spaced, 2))
    }

    @Test fun roomIsNotAnyKeyOrPlainHashOfTheCode() {
        val room = InternetRoom.roomId(code, 2)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(code.canonical.toByteArray())
        assertFalse(sha.joinToString("") { "%02x".format(it) }.startsWith(room))
    }

    @Test fun sixDigitNumbersAreNotAllowedOnTheInternet() {
        assertTrue(InternetRoom.codeAllowed(code))
        assertFalse(InternetRoom.codeAllowed(RideCode.generateDigits()))
    }

    // ---- backoff

    @Test fun lostConnectionBacksOffThenSettles() {
        val steps = (0..7).map { Backoff.delayMs(it, RelayFailure.Lost) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10000L, 10000L, 10000L, 10000L), steps)
    }

    @Test fun busyAndFullAreSlow() {
        assertEquals(15_000L, Backoff.delayMs(0, RelayFailure.Busy))
        assertEquals(15_000L, Backoff.delayMs(5, RelayFailure.Full))
    }

    @Test fun jitterIsBoundedAndNegativeAttemptsAreSafe() {
        assertEquals(500L, Backoff.delayMs(0, RelayFailure.Lost, 0.0))
        assertEquals(1500L, Backoff.delayMs(0, RelayFailure.Lost, 99.0))
        assertEquals(1000L, Backoff.delayMs(-3, RelayFailure.Lost))
    }

    // ---- what a failure means

    @Test fun classifiesFailures() {
        val c = OkHttpRelayConnector.Companion
        assertEquals(RelayFailure.Full, c.classify(0, 403))
        assertEquals(RelayFailure.Busy, c.classify(0, 429))
        assertEquals(RelayFailure.Busy, c.classify(0, 503))
        assertEquals(RelayFailure.Refused, c.classify(0, 400))
        assertEquals(RelayFailure.Refused, c.classify(0, 404))
        assertEquals(RelayFailure.Busy, c.classify(1008, null))
        assertEquals(RelayFailure.Refused, c.classify(1009, null))
        assertEquals(RelayFailure.Lost, c.classify(1006, null))
        assertEquals(RelayFailure.Lost, c.classify(0, null))
        assertEquals(RelayFailure.Lost, c.classify(0, 502))
    }
}
