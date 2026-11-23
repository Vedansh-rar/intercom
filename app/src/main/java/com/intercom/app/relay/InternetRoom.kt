package com.intercom.app.relay

import com.intercom.app.security.KeyDerivation
import com.intercom.app.security.RideCode
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The room a ride meets in on the relay, derived from the ride code so riders need nothing
 * but the code. It is a *slow* hash (PBKDF2, the same cost as the handshake) with its own
 * label, so it is unrelated to the keys: knowing the room id gives no key, and knowing a
 * key gives no room.
 *
 * What it cannot do: anyone who sees the room id - the relay's operator, or someone on the
 * network path - can test guesses against it offline at the cost of one PBKDF2 per guess.
 * That is the same exposure as a recorded handshake, and it is why a 6-digit code is not
 * allowed for internet rides.
 */
object InternetRoom {
    private val LABEL_SALT = "rider-intercom/v1/room-salt".toByteArray(Charsets.US_ASCII)
    private val LABEL_ID = "rider-intercom/v1/room-id".toByteArray(Charsets.US_ASCII)

    /** 32 lower-case hex characters. Takes about as long as a handshake: call it off the main thread. */
    fun roomId(code: RideCode, iterations: Int = KeyDerivation.DEFAULT_ITERATIONS): String {
        require(iterations >= 1)
        val chars = code.canonical.toCharArray()
        val master = try {
            val spec = PBEKeySpec(chars, LABEL_SALT, iterations, 256)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } finally {
            chars.fill('\u0000')
        }
        val id = KeyDerivation.hmac(master, LABEL_ID).copyOf(16)
        master.fill(0)
        return buildString(32) { for (b in id) append("%02x".format(b)) }
    }

    /** An internet ride needs a word code: a 6-digit number can be guessed from the room id in seconds. */
    fun codeAllowed(code: RideCode): Boolean = code is RideCode.Words
}
