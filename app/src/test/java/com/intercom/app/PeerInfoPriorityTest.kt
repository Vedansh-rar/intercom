package com.intercom.app

import com.intercom.app.protocol.PeerInfo
import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The priority byte group mode adds to PEER_INFO must not disturb anything that already reads it. */
class PeerInfoPriorityTest {

    @Test fun priorityRoundTrips() {
        val p = PeerInfo(PeerInfo.Status.Ok, "Asha", 1)
        assertEquals(p, PeerInfo.parse(p.toBytes()))
    }

    @Test fun priorityDefaultsToZero() {
        assertEquals(0, PeerInfo(PeerInfo.Status.Ok, "Ravi").priority)
    }

    @Test fun anOldPeerInfoWithoutPriorityParsesAsNormal() {
        // status, nameLen, name - exactly what a pre-group build writes.
        val old = byteArrayOf(0, 3, 'R'.code.toByte(), 'a'.code.toByte(), 'j'.code.toByte())
        val p = PeerInfo.parse(old)
        assertNotNull(p)
        assertEquals("Raj", p!!.name)
        assertEquals(0, p.priority)
    }

    @Test fun anOldReaderIgnoresTheTrailingPriorityByte() {
        // What an old parser does: status, length, then exactly `length` name bytes.
        val bytes = PeerInfo(PeerInfo.Status.Muted, "Mira", 1).toBytes()
        val len = bytes[1].toInt() and 0xFF
        assertEquals("Mira", String(bytes, 2, len, Charsets.UTF_8))
        assertEquals(1, bytes.size - (2 + len)) // one extra byte, after everything it reads
    }

    @Test fun priorityIsClampedToAByte() {
        assertEquals(255, PeerInfo.parse(PeerInfo(PeerInfo.Status.Ok, "X", 999).toBytes())!!.priority)
        assertEquals(0, PeerInfo.parse(PeerInfo(PeerInfo.Status.Ok, "X", -5).toBytes())!!.priority)
    }

    @Test fun aLongNameIsCutAndThePriorityStillLandsAfterIt() {
        val p = PeerInfo.parse(PeerInfo(PeerInfo.Status.Ok, "n".repeat(60), 1).toBytes())!!
        assertEquals(PeerInfo.NAME_BYTES, p.name.length)
        assertEquals(1, p.priority)
    }

    @Test fun truncatedInputIsRefused() {
        assertNull(PeerInfo.parse(ByteArray(0)))
        assertNull(PeerInfo.parse(byteArrayOf(0)))
        assertNull(PeerInfo.parse(byteArrayOf(0, 5, 'a'.code.toByte()))) // claims 5 name bytes
        assertNotNull(PeerInfo.parse(byteArrayOf(0, 0)))                 // an empty name is fine
    }

    @Test fun theMeshTypeIsDistinctFromEveryOtherType() {
        val all = listOf(
            Protocol.TYPE_AUDIO, Protocol.TYPE_PING, Protocol.TYPE_FLOOR_TAKEN, Protocol.TYPE_FLOOR_RELEASE,
            Protocol.TYPE_FLOOR_DENIED, Protocol.TYPE_END, Protocol.TYPE_PONG, Protocol.TYPE_SWITCH,
            Protocol.TYPE_POSITION, Protocol.TYPE_AUTH_CHALLENGE, Protocol.TYPE_AUTH_RESPONSE,
            Protocol.TYPE_SOS, Protocol.TYPE_PEER_INFO, Protocol.TYPE_MESH, Protocol.TYPE_HELLO,
        )
        assertEquals(all.size, all.toSet().size)
        assertEquals("MESH", Protocol.typeName(Protocol.TYPE_MESH))
    }
}
