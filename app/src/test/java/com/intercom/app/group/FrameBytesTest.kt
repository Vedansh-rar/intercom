package com.intercom.app.group

import com.intercom.app.protocol.Codec
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameBytesTest {
    @Test fun parsesWhatCodecEncodes() {
        val bytes = Codec.encode(Protocol.TYPE_MESH, 77, 0x80.toByte(), byteArrayOf(1, 2, 3))
        assertEquals(Message(Protocol.TYPE_MESH, 77, 0x80.toByte(), byteArrayOf(1, 2, 3)), FrameBytes.parse(bytes))
    }

    @Test fun truncatedAndEmptyInputGiveNull() {
        val bytes = Codec.encode(Protocol.TYPE_MESH, 1, 0, byteArrayOf(1, 2, 3))
        assertNull(FrameBytes.parse(ByteArray(0)))
        assertNull(FrameBytes.parse(bytes.copyOf(bytes.size - 1)))
        assertNull(FrameBytes.parse(bytes.copyOf(3)))
    }

    @Test fun garbageWithoutASyncByteGivesNull() {
        assertNull(FrameBytes.parse(ByteArray(40) { 0x11 }))
    }

    @Test fun leadingNoiseIsSkippedToTheSyncByte() {
        val frame = Codec.encode(Protocol.TYPE_PING, 3, 0, byteArrayOf(9))
        val m = FrameBytes.parse(byteArrayOf(1, 2, 3) + frame)
        assertEquals(Protocol.TYPE_PING, m!!.type)
    }
}
