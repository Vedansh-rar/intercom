package com.intercom.app.group

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The header group mode wraps around a message so it can cross more than one hop.
 *
 *   ttl(1) senderId(4) seq(4) ageMs(2) innerType(1) innerFlags(1) | inner payload
 *
 * It travels as the payload of a `TYPE_MESH` frame, itself sealed with the link's key, so
 * it is encrypted and authenticated on every hop. A relay opens it, decides, and seals it
 * again for the next neighbour; relays are therefore trusted members of the ride, which is
 * what the shared ride code already means.
 *
 *  - [ttl] counts hops left, starting at [MAX_TTL]. A receiver that relays sends ttl - 1.
 *  - [senderId] is the originator's random 32-bit ID, constant through every relay.
 *  - [seq] is the originator's own 32-bit counter, one per envelope. With [senderId] it
 *    identifies a message for de-duplication, and unlike the frame header's 16-bit
 *    sequence it will not wrap in a ride.
 *  - [ageMs] is how long the message has spent in relays, so far. The phones' clocks are
 *    not synchronised, so this is *residence time* plus an allowance per hop, not true
 *    time of flight.
 *  - [innerType] and [innerFlags] are the wrapped message's own frame type and flags.
 */
class MeshEnvelope(
    val ttl: Int,
    val senderId: Int,
    /** Unsigned 32-bit, held in a Long. */
    val seq: Long,
    val ageMs: Int,
    val innerType: Byte,
    val innerFlags: Byte,
    val body: ByteArray,
) {
    init {
        require(ttl in 1..MAX_TTL) { "ttl must be 1..$MAX_TTL" }
        require(seq in 0..0xFFFFFFFFL) { "seq must fit 32 bits" }
        require(ageMs in 0..0xFFFF) { "age must fit 16 bits" }
    }

    fun toBytes(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN)
        .put(ttl.toByte())
        .putInt(senderId)
        .putInt(seq.toInt())
        .putShort(ageMs.toShort())
        .put(innerType)
        .put(innerFlags)
        .put(body)
        .array()

    /** The copy a relay forwards: one hop used, and [extraAgeMs] more age (saturating). */
    fun relayed(extraAgeMs: Int): MeshEnvelope =
        MeshEnvelope(ttl - 1, senderId, seq, (ageMs.toLong() + extraAgeMs).coerceIn(0, 0xFFFF).toInt(),
            innerType, innerFlags, body)

    /** Hops this envelope has already travelled, given it started at [MAX_TTL]. */
    val hopsTravelled: Int get() = MAX_TTL - ttl + 1

    override fun equals(other: Any?): Boolean =
        other is MeshEnvelope && ttl == other.ttl && senderId == other.senderId &&
            seq == other.seq && ageMs == other.ageMs && innerType == other.innerType &&
            innerFlags == other.innerFlags && body.contentEquals(other.body)

    override fun hashCode(): Int =
        (((ttl * 31 + senderId) * 31 + seq.hashCode()) * 31 + innerType) * 31 + body.contentHashCode()

    override fun toString() = "Mesh(ttl=$ttl from=%08x seq=$seq age=${ageMs}ms ${body.size}B)".format(senderId)

    companion object {
        const val HEADER_SIZE = 13

        /** Relays forward only while ttl > 1, so a frame travels at most this many hops. */
        const val MAX_TTL = 3

        /** Null for anything truncated or with a ttl outside 1..[MAX_TTL]: never trust the wire. */
        fun parse(b: ByteArray): MeshEnvelope? {
            if (b.size < HEADER_SIZE) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
            val ttl = buf.get().toInt() and 0xFF
            if (ttl < 1 || ttl > MAX_TTL) return null
            val sender = buf.int
            val seq = buf.int.toLong() and 0xFFFFFFFFL
            val age = buf.short.toInt() and 0xFFFF
            val type = buf.get()
            val flags = buf.get()
            val body = ByteArray(b.size - HEADER_SIZE)
            buf.get(body)
            return MeshEnvelope(ttl, sender, seq, age, type, flags, body)
        }
    }
}
