package com.intercom.app.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupAudioTest {
    @Test fun packAndUnpack() {
        val body = GroupAudio.pack(0x1234, byteArrayOf(1, 2, 3))
        assertEquals(0x1234, GroupAudio.seq(body))
        assertArrayEquals(byteArrayOf(1, 2, 3), GroupAudio.payload(body))
    }

    @Test fun packHonoursTheLength() {
        val buf = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(byteArrayOf(1, 2), GroupAudio.payload(GroupAudio.pack(1, buf, 2)))
    }

    @Test fun sequenceIsSixteenBitsAndUnsigned() {
        assertEquals(0xFFFF, GroupAudio.seq(GroupAudio.pack(0xFFFF, byteArrayOf(0))))
        assertEquals(0, GroupAudio.seq(GroupAudio.pack(0x10000, byteArrayOf(0))))
    }

    @Test fun aBodyWithNoAudioHasNoSequence() {
        assertEquals(-1, GroupAudio.seq(ByteArray(0)))
        assertEquals(-1, GroupAudio.seq(ByteArray(2)))
    }
}
