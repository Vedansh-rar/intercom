package com.intercom.app.group

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderIdTest {
    @Test fun neverZero() {
        // A generator that returns zero first must be asked again.
        val rng = object : SecureRandom() {
            private var n = 0
            override fun nextInt(): Int = if (n++ < 3) 0 else 77
        }
        assertEquals(77, SenderId.random(rng))
    }

    @Test fun twoHundredDrawsAreAllDistinct() {
        // 200 draws from 2^32: the chance of a clash is about 5 in a million.
        val ids = List(200) { SenderId.random() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.none { it == 0 })
    }

    @Test fun usesTheFullThirtyTwoBitRange() {
        val ids = List(200) { SenderId.random() }
        assertTrue("some negative", ids.any { it < 0 })
        assertTrue("some positive", ids.any { it > 0 })
    }

    @Test fun hexIsEightDigitsAndUnsigned() {
        assertEquals("00000001", SenderId.hex(1))
        assertEquals("ffffffff", SenderId.hex(-1))
        assertNotEquals(SenderId.hex(1), SenderId.hex(2))
    }
}
