package com.intercom.app.relay

/** How long to wait before the next try at reaching the relay. */
object Backoff {
    private val STEPS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
    private const val SLOW_MS = 15_000L

    /** [attempt] counts from 0. Jitter spreads a whole convoy's retries after a tunnel. */
    fun delayMs(attempt: Int, failure: RelayFailure = RelayFailure.Lost, jitter: Double = 1.0): Long {
        val base = if (failure == RelayFailure.Busy || failure == RelayFailure.Full) {
            SLOW_MS
        } else {
            STEPS[attempt.coerceIn(0, STEPS.lastIndex)]
        }
        return (base * jitter.coerceIn(0.5, 1.5)).toLong()
    }
}
