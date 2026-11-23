package com.intercom.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Wire protocol, version 2 (version 1 peers are still accepted).
 *
 * v2 adds a capability bitfield to HELLO and new message types for position, channel
 * authentication, SOS and peer info. The framing below is unchanged from v1. See [Hello]
 * for how the version and capabilities are carried without breaking v1 builds.
 *
 * RFCOMM is a byte stream, not a datagram service: writes coalesce and split.
 * Every message therefore carries its own frame header so the reader can resynchronise
 * after a partial read or a reconnect.
 *
 *   byte 0      SYNC (0xAA)
 *   byte 1      type
 *   bytes 2-3   sequence number, unsigned 16-bit big endian
 *   byte 4      flags
 *   bytes 5-6   payload length, unsigned 16-bit big endian
 *   bytes 7..   payload
 */
object Protocol {
    /** The version this build speaks. */
    const val VERSION = 2

    /** The oldest peer version this build still talks to. */
    const val MIN_SUPPORTED_VERSION = 1

    /**
     * What goes in the 16-bit version field at the front of HELLO. v1 builds refuse a
     * peer whose field is not exactly 1, so a v2 build keeps writing 1 there and carries
     * its real version in the trailing extension (see [Hello]). Bumping the field itself
     * would make every old build reject every new one.
     */
    const val WIRE_COMPAT_VERSION = 1

    fun isSupportedVersion(v: Int): Boolean = v in MIN_SUPPORTED_VERSION..VERSION
    const val SYNC: Byte = 0xAA.toByte()
    const val HEADER_SIZE = 7
    const val MAX_PAYLOAD = 512

    // Message types
    const val TYPE_AUDIO: Byte = 0x01
    const val TYPE_PING: Byte = 0x02
    const val TYPE_FLOOR_TAKEN: Byte = 0x03
    const val TYPE_FLOOR_RELEASE: Byte = 0x04
    const val TYPE_FLOOR_DENIED: Byte = 0x05
    const val TYPE_END: Byte = 0x06
    const val TYPE_PONG: Byte = 0x07

    /**
     * "I am moving to another radio; follow me."
     *
     * Payload is one byte of [Transport] ordinal. Without this the two phones would
     * each have to notice the degradation independently and happen to pick the same
     * transport, which they will not do at the same moment. The side that decides sends
     * SWITCH first and only then tears its link down.
     */
    const val TYPE_SWITCH: Byte = 0x08

    // ---- v2 message types. Only sent to peers that advertised the matching capability;
    // ---- a v1 peer would ignore them (unknown types are skipped), but there is no point
    // ---- spending the bytes.

    /** Rider position report. See [PositionReport]. Needs [Capabilities.POSITION]. */
    const val TYPE_POSITION: Byte = 0x09

    /** Authentication challenge (a random nonce). Needs [Capabilities.AUTH]. */
    const val TYPE_AUTH_CHALLENGE: Byte = 0x0A

    /** Authentication response (a MAC over both nonces). Needs [Capabilities.AUTH]. */
    const val TYPE_AUTH_RESPONSE: Byte = 0x0B

    /** SOS alert, with the sender's last position if sharing is on. See [SosAlert]. */
    const val TYPE_SOS: Byte = 0x0C

    /** Display name and status of a rider. See [PeerInfo]. */
    const val TYPE_PEER_INFO: Byte = 0x0D

    /**
     * Group mode only: a mesh envelope wrapping another message so it can be relayed. See
     * [com.intercom.app.group.MeshEnvelope]. Two-rider calls never send it.
     */
    const val TYPE_MESH: Byte = 0x0E

    const val TYPE_HELLO: Byte = 0x10

    /** The v1 HELLO, and the fixed head of every HELLO. */
    const val HELLO_SIZE = 32

    /** A v2 HELLO: the 32-byte head plus the 5-byte extension. */
    const val HELLO_V2_SIZE = HELLO_SIZE + Hello.EXT_SIZE

    fun typeName(t: Byte): String = when (t) {
        TYPE_AUDIO -> "AUDIO"
        TYPE_PING -> "PING"
        TYPE_PONG -> "PONG"
        TYPE_SWITCH -> "SWITCH"
        TYPE_POSITION -> "POSITION"
        TYPE_AUTH_CHALLENGE -> "AUTH_CHALLENGE"
        TYPE_AUTH_RESPONSE -> "AUTH_RESPONSE"
        TYPE_SOS -> "SOS"
        TYPE_PEER_INFO -> "PEER_INFO"
        TYPE_MESH -> "MESH"
        TYPE_FLOOR_TAKEN -> "FLOOR_TAKEN"
        TYPE_FLOOR_RELEASE -> "FLOOR_RELEASE"
        TYPE_FLOOR_DENIED -> "FLOOR_DENIED"
        TYPE_END -> "END"
        TYPE_HELLO -> "HELLO"
        else -> "UNKNOWN(0x%02X)".format(t)
    }
}

/** One decoded message off the wire. [payload] is owned by the receiver. */
data class Message(
    val type: Byte,
    val seq: Int,
    val flags: Byte,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Message && type == other.type && seq == other.seq &&
            flags == other.flags && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        ((type.toInt() * 31 + seq) * 31 + flags) * 31 + payload.contentHashCode()

    override fun toString(): String =
        "Message(${Protocol.typeName(type)}, seq=$seq, ${payload.size}B)"
}

/** Serialises messages into the framed form described in [Protocol]. */
object Codec {

    fun encode(type: Byte, seq: Int, flags: Byte, payload: ByteArray?): ByteArray {
        val body = payload ?: ByteArray(0)
        require(body.size <= Protocol.MAX_PAYLOAD) { "payload too large: ${body.size}" }
        val out = ByteArray(Protocol.HEADER_SIZE + body.size)
        out[0] = Protocol.SYNC
        out[1] = type
        out[2] = ((seq ushr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = flags
        out[5] = ((body.size ushr 8) and 0xFF).toByte()
        out[6] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, Protocol.HEADER_SIZE, body.size)
        return out
    }

    fun encodeLong(type: Byte, seq: Int, value: Long): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        return encode(type, seq, 0, b)
    }

    fun readLong(payload: ByteArray, offset: Int = 0): Long =
        ByteBuffer.wrap(payload, offset, 8).order(ByteOrder.BIG_ENDIAN).long
}

/**
 * The handshake. Both sides send it immediately after the socket opens.
 *
 * The head is the v1 layout, byte for byte, so a v1 build parses it unchanged:
 *
 *   version(2) sampleRate(4) frameSamples(2) codec(1) sessionId(8) nameLen(1) name(14)
 *
 * A v2 build appends a 5-byte extension that v1 builds never read:
 *
 *   magic "R2"(2) protocolVersion(1) capabilities(2)
 *
 * and writes [Protocol.WIRE_COMPAT_VERSION] (1) in the head's version field. So:
 *  - v2 -> v1: the old build sees version 1, accepts, ignores the tail.
 *  - v1 -> v2: no extension, parsed as version 1 with no capabilities.
 *  - v2 <-> v2: both read the extension and negotiate with [Capabilities].
 *
 * [version] is the effective protocol version (1 or 2), not the raw head field.
 */
data class Hello(
    val version: Int,
    val sampleRate: Int,
    val frameSamples: Int,
    val codec: Int,
    val sessionId: Long,
    val riderName: String,
    /** [Capabilities] bits this build offers. Always 0 for a v1 peer. */
    val capabilities: Int = 0,
) {
    fun toBytes(): ByteArray {
        val v2 = version >= 2
        val size = if (v2) Protocol.HELLO_V2_SIZE else Protocol.HELLO_SIZE
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((if (v2) Protocol.WIRE_COMPAT_VERSION else version).toShort())
        buf.putInt(sampleRate)
        buf.putShort(frameSamples.toShort())
        buf.put(codec.toByte())
        buf.putLong(sessionId)
        val raw = riderName.toByteArray(Charsets.UTF_8)
        val name = if (raw.size > NAME_BYTES) raw.copyOf(NAME_BYTES) else raw
        buf.put(name.size.toByte())
        buf.put(name)
        while (buf.position() < Protocol.HELLO_SIZE) buf.put(0)
        if (v2) {
            buf.put(EXT_MAGIC_0)
            buf.put(EXT_MAGIC_1)
            buf.put(version.toByte())
            buf.putShort(capabilities.toShort())
        }
        return buf.array()
    }

    companion object {
        const val NAME_BYTES = 14
        const val CODEC_ADPCM = 1
        const val CODEC_OPUS = 2

        const val EXT_SIZE = 5
        private const val EXT_MAGIC_0: Byte = 0x52 // 'R'
        private const val EXT_MAGIC_1: Byte = 0x32 // '2'

        fun parse(bytes: ByteArray): Hello? {
            if (bytes.size < Protocol.HELLO_SIZE) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var version = buf.short.toInt() and 0xFFFF
            val sampleRate = buf.int
            val frameSamples = buf.short.toInt() and 0xFFFF
            val codec = buf.get().toInt() and 0xFF
            val sessionId = buf.long
            val nameLen = (buf.get().toInt() and 0xFF).coerceAtMost(NAME_BYTES)
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)

            var capabilities = 0
            if (bytes.size >= Protocol.HELLO_V2_SIZE &&
                bytes[Protocol.HELLO_SIZE] == EXT_MAGIC_0 &&
                bytes[Protocol.HELLO_SIZE + 1] == EXT_MAGIC_1
            ) {
                val ext = ByteBuffer.wrap(bytes, Protocol.HELLO_SIZE + 2, EXT_SIZE - 2)
                    .order(ByteOrder.BIG_ENDIAN)
                val extVersion = ext.get().toInt() and 0xFF
                if (extVersion >= 2) {
                    version = extVersion
                    capabilities = ext.short.toInt() and 0xFFFF
                }
            }
            return Hello(
                version = version,
                sampleRate = sampleRate,
                frameSamples = frameSamples,
                codec = codec,
                sessionId = sessionId,
                riderName = String(nameBytes, Charsets.UTF_8),
                capabilities = capabilities,
            )
        }

        fun newSessionId(): Long = Random.nextLong() and Long.MAX_VALUE
    }
}
