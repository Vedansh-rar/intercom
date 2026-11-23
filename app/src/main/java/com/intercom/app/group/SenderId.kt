package com.intercom.app.group

import java.security.SecureRandom

/**
 * A rider's identity inside a group ride: 32 random bits from a [SecureRandom], never
 * derived from a clock or a device identifier.
 *
 * With a convoy of a dozen riders the chance that any two draw the same ID is about one in
 * a hundred million. A collision would merge those two riders' streams for the ride; that is
 * accepted rather than engineered around, and ending and rejoining the ride clears it.
 */
object SenderId {
    fun random(rng: SecureRandom = SecureRandom()): Int {
        while (true) {
            val v = rng.nextInt()
            if (v != 0) return v // zero is reserved to mean "nobody"
        }
    }

    fun hex(id: Int): String = "%08x".format(id)
}
