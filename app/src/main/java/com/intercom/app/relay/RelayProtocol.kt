package com.intercom.app.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wire format between a phone and the relay server (`server/relay.js`). The relay only
 * names who is in the room and forwards opaque packets; everything it forwards is already
 * encrypted and authenticated between the two riders.
 *
 *   relay -> phone   0x01 WELCOME id:u32 maxPayload:u16
 *                    0x02 JOIN    id:u32
 *                    0x03 LEAVE   id:u32
 *                    0x04 DATA    src:u32 payload...
 *   phone -> relay   0x10 SEND    dst:u32 payload...
 */
object RelayProtocol {
    const val WELCOME: Byte = 0x01
    const val JOIN: Byte = 0x02
    const val LEAVE: Byte = 0x03
    const val DATA: Byte = 0x04
    const val SEND: Byte = 0x10

    sealed interface Incoming {
        class Welcome(val id: Int, val maxPayload: Int) : Incoming
        class Join(val id: Int) : Incoming
        class Leave(val id: Int) : Incoming
        class Data(val src: Int, val payload: ByteArray) : Incoming
    }

    /** Null for anything short, unknown or naming id 0: never trust the wire. */
    fun parse(b: ByteArray): Incoming? {
        if (b.isEmpty()) return null
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        buf.get()
        return when (b[0]) {
            WELCOME -> if (b.size == 7) idOrNull(buf.int)?.let { Incoming.Welcome(it, buf.short.toInt() and 0xFFFF) } else null
            JOIN -> if (b.size == 5) idOrNull(buf.int)?.let { Incoming.Join(it) } else null
            LEAVE -> if (b.size == 5) idOrNull(buf.int)?.let { Incoming.Leave(it) } else null
            DATA -> if (b.size >= 5) idOrNull(buf.int)?.let { Incoming.Data(it, b.copyOfRange(5, b.size)) } else null
            else -> null
        }
    }

    private fun idOrNull(id: Int): Int? = if (id == 0) null else id

    /** A packet for the member [dst]. */
    fun send(dst: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(SEND).putInt(dst).put(payload).array()

    /** The endpoint name used for a relay id. */
    fun hex(id: Int): String = "%08x".format(id)

    fun fromHex(s: String): Int? =
        if (s.length == 8) s.toLongOrNull(16)?.toInt() else null
}
