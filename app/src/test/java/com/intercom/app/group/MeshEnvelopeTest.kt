package com.intercom.app.group

import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshEnvelopeTest {
    private fun env(
        ttl: Int = 3, sender: Int = 0x1234ABCD, seq: Long = 7, age: Int = 0,
        body: ByteArray = byteArrayOf(1, 2, 3),
    ) = MeshEnvelope(ttl, sender, seq, age, Protocol.TYPE_AUDIO, 1, body)

    @Test fun roundTripsEveryField() {
        val e = env(ttl = 2, seq = 99, age = 41)
        assertEquals(e, MeshEnvelope.parse(e.toBytes()))
    }

    @Test fun headerIsThirteenBytes() {
        assertEquals(13, MeshEnvelope.HEADER_SIZE)
        assertEquals(13 + 3, env().toBytes().size)
        assertEquals(13, env(body = ByteArray(0)).toBytes().size)
    }

    @Test fun senderIdMayBeNegativeAndSurvives() {
        val e = env(sender = -5)
        assertEquals(-5, MeshEnvelope.parse(e.toBytes())!!.senderId)
    }

    @Test fun sequenceIsUnsigned32Bit() {
        val e = env(seq = 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, MeshEnvelope.parse(e.toBytes())!!.seq)
    }

    @Test fun truncatedInputIsRejected() {
        val b = env().toBytes()
        for (n in 0 until MeshEnvelope.HEADER_SIZE) assertNull(MeshEnvelope.parse(b.copyOf(n)))
    }

    @Test fun ttlOutsideTheLegalRangeIsRejectedOnTheWire() {
        val b = env().toBytes()
        b[0] = 0
        assertNull(MeshEnvelope.parse(b))
        b[0] = (MeshEnvelope.MAX_TTL + 1).toByte()
        assertNull(MeshEnvelope.parse(b))
        b[0] = 0xFF.toByte()
        assertNull(MeshEnvelope.parse(b))
    }

    @Test fun anIllegalEnvelopeCannotBeBuilt() {
        val bad = listOf(
            { env(ttl = 0) }, { env(ttl = 4) }, { env(seq = -1) },
            { env(seq = 0x1_0000_0000L) }, { env(age = -1) }, { env(age = 0x1_0000) },
        )
        for (f in bad) {
            try { f(); org.junit.Assert.fail("should have thrown") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun relayingUsesOneHopAndAddsAge() {
        val r = env(ttl = 3, age = 10).relayed(25)
        assertEquals(2, r.ttl)
        assertEquals(35, r.ageMs)
        assertEquals(0x1234ABCD, r.senderId)
        assertEquals(7L, r.seq)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.body)
    }

    @Test fun ageSaturatesInsteadOfWrapping() {
        assertEquals(0xFFFF, env(age = 0xFFF0).relayed(1000).ageMs)
        assertEquals(0, env(age = 0).relayed(-50).ageMs)
    }

    @Test fun hopsTravelledCountsFromTheOriginator() {
        assertEquals(1, env(ttl = 3).hopsTravelled)
        assertEquals(2, env(ttl = 2).hopsTravelled)
        assertEquals(3, env(ttl = 1).hopsTravelled)
    }

    @Test fun ttlNeverReachesZeroThroughRelayRules() {
        // The router only relays while ttl > 1, so relayed() is never asked for ttl 0.
        var e = env(ttl = MeshEnvelope.MAX_TTL)
        var hops = 1
        while (e.ttl > 1) { e = e.relayed(0); hops++ }
        assertEquals(MeshEnvelope.MAX_TTL, hops)
    }

    @Test fun parsedBodyIsACopy() {
        val b = env().toBytes()
        val e = MeshEnvelope.parse(b)!!
        b[b.size - 1] = 99
        assertNotNull(e)
        assertTrue(e.body[2] == 3.toByte())
    }
}
