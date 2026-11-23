package com.intercom.app.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun parsesWelcome() {
        val m = RelayProtocol.parse(b(1, 0x12, 0x34, 0x56, 0x78, 0x08, 0x00)) as RelayProtocol.Incoming.Welcome
        assertEquals(0x12345678, m.id)
        assertEquals(2048, m.maxPayload)
    }

    @Test fun parsesJoinAndLeave() {
        assertEquals(0x01020304, (RelayProtocol.parse(b(2, 1, 2, 3, 4)) as RelayProtocol.Incoming.Join).id)
        assertEquals(-1, (RelayProtocol.parse(b(3, 255, 255, 255, 255)) as RelayProtocol.Incoming.Leave).id)
    }

    @Test fun parsesDataWithAndWithoutPayload() {
        val m = RelayProtocol.parse(b(4, 0, 0, 0, 9, 7, 8, 9)) as RelayProtocol.Incoming.Data
        assertEquals(9, m.src)
        assertArrayEquals(b(7, 8, 9), m.payload)
        assertEquals(0, (RelayProtocol.parse(b(4, 0, 0, 0, 9)) as RelayProtocol.Incoming.Data).payload.size)
    }

    @Test fun rejectsAnythingMalformed() {
        assertNull(RelayProtocol.parse(ByteArray(0)))
        assertNull(RelayProtocol.parse(b(1, 0, 0, 0, 1)))            // welcome too short
        assertNull(RelayProtocol.parse(b(1, 0, 0, 0, 1, 8, 0, 0)))   // welcome too long
        assertNull(RelayProtocol.parse(b(2, 1, 2, 3)))               // join too short
        assertNull(RelayProtocol.parse(b(2, 1, 2, 3, 4, 5)))         // join too long
        assertNull(RelayProtocol.parse(b(4, 0, 0)))                  // data too short
        assertNull(RelayProtocol.parse(b(0x10, 0, 0, 0, 1)))         // a phone-to-relay type
        assertNull(RelayProtocol.parse(b(0x7f, 0, 0, 0, 1)))         // unknown type
    }

    @Test fun idZeroIsNeverAccepted() {
        assertNull(RelayProtocol.parse(b(1, 0, 0, 0, 0, 8, 0)))
        assertNull(RelayProtocol.parse(b(2, 0, 0, 0, 0)))
        assertNull(RelayProtocol.parse(b(3, 0, 0, 0, 0)))
        assertNull(RelayProtocol.parse(b(4, 0, 0, 0, 0, 1)))
    }

    @Test fun buildsSend() {
        assertArrayEquals(b(0x10, 0xAB, 0xCD, 0xEF, 0x01, 5, 6), RelayProtocol.send(0xABCDEF01.toInt(), b(5, 6)))
        assertArrayEquals(b(0x10, 0, 0, 0, 1), RelayProtocol.send(1, ByteArray(0)))
    }

    @Test fun hexRoundTrips() {
        for (id in listOf(1, 0x7fffffff, -2, 0x0000abcd)) {
            val h = RelayProtocol.hex(id)
            assertEquals(8, h.length)
            assertEquals(id, RelayProtocol.fromHex(h))
        }
        assertNull(RelayProtocol.fromHex("xyz"))
        assertNull(RelayProtocol.fromHex("123"))
    }
}
