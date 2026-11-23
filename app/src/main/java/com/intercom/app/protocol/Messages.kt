package com.intercom.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payloads for the v2 message types. Each has `toBytes()` and a `parse()` that returns
 * null on a payload that is too short, so a corrupt or truncated frame is dropped rather
 * than throwing on the receive path.
 */

/** A rider's position, 22 bytes. Sent only when both sides have [Capabilities.POSITION]. */
data class PositionReport(
    /** Degrees x 1e7. */
    val latE7: Int,
    val lonE7: Int,
    /** Ground speed in cm/s. */
    val speedCms: Int,
    /** Heading in degrees x 10, 0..3599. */
    val headingDeci: Int,
    /** Horizontal accuracy in metres, saturating at 65535. */
    val accuracyM: Int,
    /** Sender clock, milliseconds; convert with the link's clock offset on receipt. */
    val timestampMs: Long,
) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        .putInt(latE7)
        .putInt(lonE7)
        .putShort(speedCms.coerceIn(0, 0xFFFF).toShort())
        .putShort(headingDeci.coerceIn(0, 3599).toShort())
        .putShort(accuracyM.coerceIn(0, 0xFFFF).toShort())
        .putLong(timestampMs)
        .array()

    companion object {
        const val SIZE = 22

        fun parse(b: ByteArray): PositionReport? {
            if (b.size < SIZE) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
            return PositionReport(
                latE7 = buf.int,
                lonE7 = buf.int,
                speedCms = buf.short.toInt() and 0xFFFF,
                headingDeci = buf.short.toInt() and 0xFFFF,
                accuracyM = buf.short.toInt() and 0xFFFF,
                timestampMs = buf.long,
            )
        }
    }
}

/** A random nonce the other side must mix into its response. */
class AuthChallenge(val nonce: ByteArray) {
    init { require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" } }

    fun toBytes(): ByteArray = nonce.copyOf()

    override fun equals(other: Any?) = other is AuthChallenge && nonce.contentEquals(other.nonce)
    override fun hashCode() = nonce.contentHashCode()

    companion object {
        const val NONCE_SIZE = 16
        fun parse(b: ByteArray): AuthChallenge? =
            if (b.size < NONCE_SIZE) null else AuthChallenge(b.copyOf(NONCE_SIZE))
    }
}

/** HMAC-SHA256 proving knowledge of the ride code; computed in Phase 3. */
class AuthResponse(val mac: ByteArray) {
    init { require(mac.size == MAC_SIZE) { "mac must be $MAC_SIZE bytes" } }

    fun toBytes(): ByteArray = mac.copyOf()

    override fun equals(other: Any?) = other is AuthResponse && mac.contentEquals(other.mac)
    override fun hashCode() = mac.contentHashCode()

    companion object {
        const val MAC_SIZE = 32
        fun parse(b: ByteArray): AuthResponse? =
            if (b.size < MAC_SIZE) null else AuthResponse(b.copyOf(MAC_SIZE))
    }
}

/** An SOS. [latE7]/[lonE7] are null when the sender is not sharing location. */
data class SosAlert(
    val latE7: Int?,
    val lonE7: Int?,
    val timestampMs: Long,
) {
    init { require((latE7 == null) == (lonE7 == null)) { "lat and lon go together" } }

    fun toBytes(): ByteArray {
        val has = latE7 != null && lonE7 != null
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(if (has) 1 else 0)
            .putInt(latE7 ?: 0)
            .putInt(lonE7 ?: 0)
            .putLong(timestampMs)
            .array()
    }

    companion object {
        const val SIZE = 17

        fun parse(b: ByteArray): SosAlert? {
            if (b.size < SIZE) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
            val has = (buf.get().toInt() and 1) != 0
            val lat = buf.int
            val lon = buf.int
            val ts = buf.long
            return SosAlert(if (has) lat else null, if (has) lon else null, ts)
        }
    }
}

/** A rider's display name and status. */
data class PeerInfo(val status: Status, val name: String) {

    enum class Status(val wire: Int) {
        Ok(0), Muted(1), Deafened(2), Busy(3);

        companion object {
            fun fromWire(v: Int): Status = entries.firstOrNull { it.wire == v } ?: Ok
        }
    }

    fun toBytes(): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val n = if (raw.size > NAME_BYTES) raw.copyOf(NAME_BYTES) else raw
        return ByteBuffer.allocate(2 + n.size).put(status.wire.toByte()).put(n.size.toByte())
            .put(n).array()
    }

    companion object {
        const val NAME_BYTES = 20

        fun parse(b: ByteArray): PeerInfo? {
            if (b.size < 2) return null
            val len = (b[1].toInt() and 0xFF).coerceAtMost(NAME_BYTES)
            if (b.size < 2 + len) return null
            return PeerInfo(
                Status.fromWire(b[0].toInt() and 0xFF),
                String(b, 2, len, Charsets.UTF_8),
            )
        }
    }
}
