package com.intercom.app.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns the ride code into keys. Everything here is a standard primitive from
 * `javax.crypto` (PBKDF2-HMAC-SHA256, HMAC-SHA256); nothing is hand-rolled.
 *
 * ## Why PBKDF2 and not a PAKE
 *
 * The prompt asks for a PAKE (SPAKE2 or similar) if a small, well-maintained library
 * exists. None does that I could responsibly pull into an Android app: the options are
 * unmaintained or unaudited, or pull in large native or JVM dependencies. Writing a PAKE
 * myself would break the rule against hand-rolled crypto. So this build uses the stated
 * fallback: PBKDF2-HMAC-SHA256 with a per-session salt, plus a rate limit on attempts
 * ([AuthThrottle]).
 *
 * ## What that does and does not give
 *
 * - The code is never sent, and neither is a hash of it. An observer of the radio link
 *   sees two random nonces and two HMACs.
 * - But those HMACs *are* an offline guessing oracle: someone who records one handshake can
 *   test candidate codes against it at their leisure. A PAKE would prevent that; this
 *   cannot. What PBKDF2 buys is cost per guess, and the per-session salt (made of both
 *   nonces and both session IDs) means the work cannot be shared across recorded
 *   handshakes or precomputed into a table. A fixed salt would let an attacker build one
 *   table of every 6-digit code and make the stretching worthless.
 * - So strength is the code's entropy: 5 words is 40 bits, 6 words 48 bits, but a
 *   6-digit number is only about 20 bits and falls to a recorded handshake quickly.
 *   The UI says so. Against an *active* attacker with no recording, the throttle limits
 *   guesses to a handful per minute.
 */
object KeyDerivation {

    /**
     * About a quarter to half a second on a mid-range phone, which is paid once per
     * connection. Higher is better for the offline-guess cost but every reconnect and
     * transport switch waits for it, so it is a compromise.
     */
    const val DEFAULT_ITERATIONS = 150_000

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 16

    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val HMAC = "HmacSHA256"

    /** Domain separation: these strings keep keys for different uses from ever colliding. */
    private val LABEL_SALT = "rider-intercom/v1/salt".toByteArray(Charsets.US_ASCII)
    private val LABEL_AUTH = "rider-intercom/v1/auth-key".toByteArray(Charsets.US_ASCII)
    private val LABEL_ENC = "rider-intercom/v1/enc-key".toByteArray(Charsets.US_ASCII)

    /**
     * The keys for one connection. [auth] signs the challenge responses, [enc] is the AES key
     * for frames. They are independent, so leaking one does not give the other.
     */
    class SessionKeys(val auth: ByteArray, val enc: ByteArray) {
        init {
            require(auth.size == KEY_BYTES && enc.size == KEY_BYTES)
        }

        /** Wipes the key material; called when the connection ends. */
        fun wipe() {
            auth.fill(0)
            enc.fill(0)
        }
    }

    /**
     * Derives this connection's keys.
     *
     * The salt is a hash of both nonces and both session IDs in a canonical order, so both
     * phones compute the same one without either having to be "first". The nonces make
     * every connection's salt unique even when the same two phones reconnect.
     */
    fun derive(
        code: RideCode,
        nonceA: ByteArray,
        nonceB: ByteArray,
        sessionIdA: Long,
        sessionIdB: Long,
        iterations: Int = DEFAULT_ITERATIONS,
    ): SessionKeys {
        require(iterations >= 1)
        val salt = transcriptSalt(nonceA, nonceB, sessionIdA, sessionIdB)
        val chars = code.canonical.toCharArray()
        val master = try {
            val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
            try {
                SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } finally {
            chars.fill('\u0000')
        }
        val keys = SessionKeys(
            auth = hkdfExpand(master, LABEL_AUTH),
            enc = hkdfExpand(master, LABEL_ENC),
        )
        master.fill(0)
        return keys
    }

    /**
     * SHA-256 over the label and the two nonces and two session IDs, each pair sorted so the
     * order does not depend on which phone is computing it.
     */
    internal fun transcriptSalt(
        nonceA: ByteArray,
        nonceB: ByteArray,
        sessionIdA: Long,
        sessionIdB: Long,
    ): ByteArray {
        val (n1, n2) = if (compareUnsigned(nonceA, nonceB) <= 0) nonceA to nonceB else nonceB to nonceA
        val (s1, s2) = if (sessionIdA <= sessionIdB) sessionIdA to sessionIdB else sessionIdB to sessionIdA
        val md = MessageDigest.getInstance("SHA-256")
        md.update(LABEL_SALT)
        md.update(n1)
        md.update(n2)
        md.update(ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putLong(s1).putLong(s2).array())
        return md.digest()
    }

    /**
     * HKDF-Expand (RFC 5869) for a single output block: HMAC(prk, info || 0x01). One block
     * is exactly 32 bytes, which is all either key needs.
     */
    internal fun hkdfExpand(prk: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        mac.update(info)
        mac.update(0x01)
        return mac.doFinal()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Constant-time comparison, so a wrong MAC does not leak how many bytes matched. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
