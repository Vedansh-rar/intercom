package com.intercom.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthThrottleTest {
    private var t = 1_000_000L
    private val throttle = AuthThrottle { t }

    @Test fun firstMistakesAreFree() {
        throttle.recordFailure()
        throttle.recordFailure()
        assertFalse(throttle.isLocked)
        assertEquals(0L, throttle.remainingMs())
    }

    @Test fun thirdFailureLocksOutThenItExpires() {
        repeat(3) { throttle.recordFailure() }
        assertTrue(throttle.isLocked)
        assertEquals(AuthThrottle.BASE_LOCKOUT_MS, throttle.remainingMs())
        t += AuthThrottle.BASE_LOCKOUT_MS - 1
        assertTrue(throttle.isLocked)
        t += 1
        assertFalse(throttle.isLocked)
    }

    @Test fun lockoutDoublesUpToACap() {
        repeat(3) { throttle.recordFailure() }
        t += 15_000; throttle.recordFailure()
        assertEquals(30_000L, throttle.remainingMs())
        t += 30_000; throttle.recordFailure()
        assertEquals(60_000L, throttle.remainingMs())
        repeat(20) { t += 400_000; throttle.recordFailure() }
        assertEquals(AuthThrottle.MAX_LOCKOUT_MS, throttle.remainingMs())
    }

    @Test fun successClearsEverything() {
        repeat(5) { throttle.recordFailure() }
        throttle.recordSuccess()
        assertFalse(throttle.isLocked)
        assertEquals(0, throttle.failureCount)
        throttle.recordFailure(); throttle.recordFailure()
        assertFalse("the free allowance is back", throttle.isLocked)
    }
}

class ReplayWindowTest {
    @Test fun acceptsEachCounterOnce() {
        val w = ReplayWindow()
        assertTrue(w.accept(5)); assertFalse(w.accept(5))
        assertTrue(w.accept(6)); assertFalse(w.accept(6)); assertFalse(w.accept(5))
    }

    @Test fun acceptsLateArrivalsInsideTheWindowOnly() {
        val w = ReplayWindow(64)
        assertTrue(w.accept(100))
        assertTrue(w.accept(40))   // 60 back, inside
        assertFalse(w.accept(36))  // 64 back, outside
        assertFalse(w.accept(40))
    }

    @Test fun bigJumpForwardClearsHistoryButStillRefusesRepeats() {
        val w = ReplayWindow()
        assertTrue(w.accept(1))
        assertTrue(w.accept(1_000_000))
        assertFalse(w.accept(1_000_000))
        assertFalse(w.accept(1))
    }

    @Test fun worksNearTheTopOfTheCounterSpace() {
        val w = ReplayWindow()
        assertTrue(w.accept(0xFFFFFFFFL))
        assertFalse(w.accept(0xFFFFFFFFL))
        assertTrue(w.accept(0xFFFFFFFEL))
    }
}

class NonceCacheTest {
    @Test fun rememberedNonceIsRefusedSecondTime() {
        val c = NonceCache()
        val n = ByteArray(16) { 3 }
        assertTrue(c.add(n)); assertFalse(c.add(n.copyOf()))
        assertTrue(c.contains(n))
    }

    @Test fun staysBounded() {
        val c = NonceCache(capacity = 8)
        val first = ByteArray(16) { 0 }
        c.add(first)
        repeat(20) { i -> c.add(ByteArray(16) { (i + 1).toByte() }) }
        assertFalse("the oldest has been evicted", c.contains(first))
    }
}
