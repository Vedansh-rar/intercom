package com.intercom.app.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEndpointNameTest {
    @Test fun formatThenParseRoundTrips() {
        val n = GroupEndpointName.format("Ravi", 0x0A0B0C0D)
        assertEquals("Ravi#0a0b0c0d", n)
        assertEquals("Ravi" to 0x0A0B0C0D, GroupEndpointName.parse(n))
    }

    @Test fun negativeIdsRoundTrip() {
        val n = GroupEndpointName.format("A", -2)
        assertEquals(-2, GroupEndpointName.parse(n).second)
    }

    @Test fun aHashInTheNameDoesNotBreakParsing() {
        val n = GroupEndpointName.format("C#sharp", 5)
        val (name, id) = GroupEndpointName.parse(n)
        assertEquals("C sharp", name)
        assertEquals(5, id)
    }

    @Test fun longNamesAreCut() {
        val n = GroupEndpointName.format("x".repeat(100), 1)
        assertEquals(GroupEndpointName.NAME_CHARS + 9, n.length)
    }

    @Test fun malformedNamesYieldANullId() {
        assertNull(GroupEndpointName.parse("nohash").second)
        assertNull(GroupEndpointName.parse("name#12").second)
        assertNull(GroupEndpointName.parse("name#zzzzzzzz").second)
    }

    @Test fun exactlyOneOfTwoPhonesInitiates() {
        val a = 5
        val b = -3 // 0xFFFFFFFD, larger when unsigned
        assertTrue(GroupEndpointName.shouldInitiate(a, b))
        assertFalse(GroupEndpointName.shouldInitiate(b, a))
        assertFalse(GroupEndpointName.shouldInitiate(a, a))
    }

    @Test fun theNameCarriesNoSecret() {
        // The format is name + hex ID only. There is nowhere for a code or its hash to go.
        val n = GroupEndpointName.format("Rider", 1)
        assertTrue(Regex("^[^#]*#[0-9a-f]{8}$").matches(n))
    }
}
