package com.intercom.app.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM over a frame's payload, from `javax.crypto`.
 *
 * ## Wire form
 *
 * An encrypted frame keeps the normal 7-byte header, with [ENCRYPTED_FLAG] set in the
 * flags byte, and its payload becomes
 *
 *   counter (4 bytes, big endian) | ciphertext | GCM tag (16 bytes)
 *
 * so every frame costs [OVERHEAD] = 20 bytes. Opus at 24 kbit/s is 60 bytes a frame, so
 * that is a third on top, which is acceptable on RFCOMM and Wi-Fi Direct.
 *
 * ## Nonce
 *
 * GCM is catastrophic if a (key, nonce) pair is ever reused, so the nonce is built, not
 * drawn: `direction (1 byte) | 0 0 0 | 0 0 0 0 | counter (4 bytes)`. The counter is a
 * dedicated 32-bit value per direction, incremented for every encrypted frame of every
 * type. It is *not* the header's sequence number: that is 16 bits and wraps after about
 * 21 minutes of audio, which would reuse nonces mid-call. The direction byte stops the two
 * phones, which share one key, from ever producing the same nonce. Each connection derives
 * a fresh key from fresh nonces ([KeyDerivation]), so counters restarting at zero on a new
 * connection are safe. At 2^32 frames (over two years of continuous audio) sealing stops
 * rather than wrapping.
 *
 * ## Associated data
 *
 * The header (type, sequence, flags, payload length) is authenticated but not encrypted,
 * so flipping any header bit, or moving a payload under another header, fails the tag.
 *
 * @param key 32-byte AES key
 * @param sendDirection 0 or 1; the two ends must use different values
 */
class FrameCipher(
    key: ByteArray,
    private val sendDirection: Int,
    startCounter: Long = 0L,
) {
    init {
        require(key.size == KeyDerivation.KEY_BYTES) { "AES-256 needs a 32-byte key" }
        require(sendDirection == 0 || sendDirection == 1)
        require(startCounter in 0..MAX_COUNTER)
    }

    private val secretKey = SecretKeySpec(key.copyOf(), "AES")
    private val nextCounter = AtomicLong(startCounter)
    private val replay = ReplayWindow()

    /** Frames refused on receipt: bad tag, replay, malformed. For the stats line. */
    @Volatile var rejected: Long = 0L
        private set

    /** Frames sealed so far. */
    val sealedCount: Long get() = nextCounter.get()

    /** What [seal] hands back: the flags and payload to put in the frame. */
    class Sealed(val flags: Byte, val payload: ByteArray)

    /**
     * Encrypts [plaintext]. Thread-safe: the capture thread seals audio while the floor
     * code seals control messages, and the atomic counter keeps their nonces distinct.
     *
     * @throws IllegalStateException when the counter space is exhausted
     */
    fun seal(type: Byte, seq: Int, flags: Byte, plaintext: ByteArray): Sealed {
        val counter = nextCounter.getAndIncrement()
        check(counter <= MAX_COUNTER) { "frame counter exhausted; reconnect to rekey" }
        val outFlags = (flags.toInt() or ENCRYPTED_FLAG.toInt()).toByte()
        val payloadLen = plaintext.size + OVERHEAD
        require(payloadLen <= MAX_SEALED_PAYLOAD) { "payload too large to seal: ${plaintext.size}" }

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_BITS, nonce(sendDirection, counter)),
        )
        cipher.updateAAD(aad(type, seq, outFlags, payloadLen))
        val body = cipher.doFinal(plaintext) // ciphertext || tag

        val out = ByteArray(COUNTER_BYTES + body.size)
        ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(counter.toInt())
        System.arraycopy(body, 0, out, COUNTER_BYTES, body.size)
        return Sealed(outFlags, out)
    }

    /**
     * Decrypts a frame, or returns null if it is malformed, has a bad tag (tampered header
     * or body, or the wrong key) or repeats a counter already accepted.
     *
     * The replay window is only updated after the tag has verified, so an attacker cannot
     * burn counters by sending garbage.
     */
    fun open(type: Byte, seq: Int, flags: Byte, payload: ByteArray): ByteArray? {
        if (flags.toInt() and ENCRYPTED_FLAG.toInt() == 0 || payload.size < OVERHEAD) {
            rejected++
            return null
        }
        val counter = ByteBuffer.wrap(payload, 0, COUNTER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, nonce(1 - sendDirection, counter)),
            )
            cipher.updateAAD(aad(type, seq, flags, payload.size))
            cipher.doFinal(payload, COUNTER_BYTES, payload.size - COUNTER_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            rejected++
            return null
        }
        if (!replay.accept(counter)) {
            rejected++
            return null
        }
        return plain
    }

    companion object {
        const val ENCRYPTED_FLAG: Byte = 0x80.toByte()
        const val COUNTER_BYTES = 4
        const val TAG_BYTES = 16
        const val OVERHEAD = COUNTER_BYTES + TAG_BYTES
        const val MAX_COUNTER = 0xFFFFFFFFL

        /** Matches the protocol's payload ceiling. */
        const val MAX_SEALED_PAYLOAD = 512

        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = TAG_BYTES * 8

        fun isEncrypted(flags: Byte): Boolean = flags.toInt() and ENCRYPTED_FLAG.toInt() != 0

        /** The flags with the encryption bit cleared, i.e. what the codec check compares. */
        fun clearEncryptedBit(flags: Byte): Byte = (flags.toInt() and 0x7F).toByte()

        /** Exposed so tests can assert uniqueness of exactly what GCM sees. */
        internal fun nonce(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .put(direction.toByte())
                .put(0).put(0).put(0)
                .putInt(0)
                .putInt(counter.toInt())
                .array()

        /** The frame header as it is on the wire, minus the sync byte. */
        internal fun aad(type: Byte, seq: Int, flags: Byte, payloadLen: Int): ByteArray =
            byteArrayOf(
                type,
                ((seq ushr 8) and 0xFF).toByte(),
                (seq and 0xFF).toByte(),
                flags,
                ((payloadLen ushr 8) and 0xFF).toByte(),
                (payloadLen and 0xFF).toByte(),
            )
    }
}
