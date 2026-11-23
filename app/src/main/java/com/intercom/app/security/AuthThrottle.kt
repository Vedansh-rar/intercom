package com.intercom.app.security

/**
 * Slows down guessing of the ride code.
 *
 * Each wrong code counts as a failure. The first [FREE_FAILURES] are free (people mistype);
 * after that the next attempt is refused for [BASE_LOCKOUT_MS], doubling with each further
 * failure up to [MAX_LOCKOUT_MS]. A correct code resets it.
 *
 * It is a rate limit on *online* guessing - someone standing next to a phone trying codes
 * against it. It cannot stop an offline attack on a recorded handshake; see [KeyDerivation].
 *
 * The clock is injected so tests can move time.
 */
class AuthThrottle(private val now: () -> Long = { System.currentTimeMillis() }) {

    private var failures = 0
    private var lockedUntilMs = 0L

    @Synchronized
    fun recordFailure() {
        failures++
        val over = failures - FREE_FAILURES
        if (over >= 1) {
            val shift = (over - 1).coerceAtMost(MAX_SHIFT)
            val lock = (BASE_LOCKOUT_MS shl shift).coerceAtMost(MAX_LOCKOUT_MS)
            lockedUntilMs = now() + lock
        }
    }

    @Synchronized
    fun recordSuccess() {
        failures = 0
        lockedUntilMs = 0L
    }

    /** Milliseconds until the next attempt is allowed; 0 if it is allowed now. */
    @Synchronized
    fun remainingMs(): Long = (lockedUntilMs - now()).coerceAtLeast(0)

    val isLocked: Boolean get() = remainingMs() > 0

    @get:Synchronized
    val failureCount: Int get() = failures

    companion object {
        const val FREE_FAILURES = 2
        const val BASE_LOCKOUT_MS = 15_000L
        const val MAX_LOCKOUT_MS = 300_000L
        private const val MAX_SHIFT = 10
    }
}
