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

/**
 * A random nonce the other side must mix into its response, plus the sender's session ID.
 *
 * The session ID rides along (payload 24 bytes) so the receiver does not depend on having
 * already processed the sender's HELLO; it is covered by the response MAC, so it cannot be
 * altered in flight. An older 16-byte payload parses with [sessionId] = 0, meaning
 * "use the HELLO's".
 */
class AuthChallenge(val nonce: ByteArray, val sessionId: Long = 0L) {
    init { require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" } }

    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        .put(nonce).putLong(sessionId).array()

    override fun equals(other: Any?) =
        other is AuthChallenge && nonce.contentEquals(other.nonce) && sessionId == other.sessionId
    override fun hashCode() = nonce.contentHashCode() * 31 + sessionId.hashCode()

    companion object {
        const val NONCE_SIZE = 16
        const val SIZE = NONCE_SIZE + 8
        fun parse(b: ByteArray): AuthChallenge? {
            if (b.size < NONCE_SIZE) return null
            val sid = if (b.size >= SIZE) {
                ByteBuffer.wrap(b, NONCE_SIZE, 8).order(ByteOrder.BIG_ENDIAN).long
            } else 0L
            return AuthChallenge(b.copyOf(NONCE_SIZE), sid)
        }
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

/**
 * An SOS. [latE7]/[lonE7] are null when the sender is not sharing location.
 *
 * [timestampMs] is when the alert was sent, on the sender's clock; it is the same in every
 * resend, which is how the receiver tells a repeat from a new alert. [positionAgeSec] is how
 * old the position already was when it was sent, rounded up to 5 s and capped at 635 s, or
 * null if the sender did not say. It rides in the spare bits of the first byte, so a build
 * that predates it reads the same message and simply ignores it.
 */
data class SosAlert(
    val latE7: Int?,
    val lonE7: Int?,
    val timestampMs: Long,
    val positionAgeSec: Int? = null,
) {
    init { require((latE7 == null) == (lonE7 == null)) { "lat and lon go together" } }

    fun toBytes(): ByteArray {
        val has = latE7 != null && lonE7 != null
        val ageUnits = if (has && positionAgeSec != null)
            ((positionAgeSec + AGE_UNIT_SEC - 1) / AGE_UNIT_SEC).coerceIn(0, MAX_AGE_UNITS) + 1
        else 0
        val flags = (if (has) 1 else 0) or (ageUnits shl 1)
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(flags.toByte())
            .putInt(latE7 ?: 0)
            .putInt(lonE7 ?: 0)
            .putLong(timestampMs)
            .array()
    }

    companion object {
        const val SIZE = 17
        private const val AGE_UNIT_SEC = 5
        // 7 spare bits: 0 means "not stated", 1..127 means 0..126 units.
        private const val MAX_AGE_UNITS = 126

        fun parse(b: ByteArray): SosAlert? {
            if (b.size < SIZE) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
            val flags = buf.get().toInt() and 0xFF
            val has = (flags and 1) != 0
            val units = flags ushr 1
            val lat = buf.int
            val lon = buf.int
            val ts = buf.long
            val age = if (has && units > 0) (units - 1) * AGE_UNIT_SEC else null
            return SosAlert(if (has) lat else null, if (has) lon else null, ts, age)
        }
    }
}

/**
 * A rider's display name and status. [priority] is group-mode only (0 normal, 1 host) and
 * rides after the name, where a reader that predates it never looks.
 */
data class PeerInfo(val status: Status, val name: String, val priority: Int = 0) {

    enum class Status(val wire: Int) {
        Ok(0), Muted(1), Deafened(2), Busy(3);

        companion object {
            fun fromWire(v: Int): Status = entries.firstOrNull { it.wire == v } ?: Ok
        }
    }

    fun toBytes(): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val n = if (raw.size > NAME_BYTES) raw.copyOf(NAME_BYTES) else raw
        return ByteBuffer.allocate(2 + n.size + 1).put(status.wire.toByte()).put(n.size.toByte())
            .put(n).put(priority.coerceIn(0, 255).toByte()).array()
    }

    companion object {
        const val NAME_BYTES = 20

        fun parse(b: ByteArray): PeerInfo? {
            if (b.size < 2) return null
            val len = (b[1].toInt() and 0xFF).coerceAtMost(NAME_BYTES)
            if (b.size < 2 + len) return null
            val priority = if (b.size > 2 + len) b[2 + len].toInt() and 0xFF else 0
            return PeerInfo(
                Status.fromWire(b[0].toInt() and 0xFF),
                String(b, 2, len, Charsets.UTF_8),
                priority,
            )
        }
    }
}
