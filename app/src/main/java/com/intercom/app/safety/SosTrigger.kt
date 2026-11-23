package com.intercom.app.safety

/**
 * The confirm step in front of the SOS button, so it cannot be sent by a glove brushing the
 * screen or a bump in the road.
 *
 * Either of two deliberate gestures sends it:
 * - a tap, then a second tap on the same button within [ARM_WINDOW_MS] (the first tap only
 *   "arms" it and the button says so), or
 * - holding the button down for [HOLD_MS] (a progress ring fills while it is held).
 *
 * A press that lasts longer than a tap but shorter than the hold does nothing, so releasing
 * early is a safe way out. Pure logic with the clock passed in, so it is unit-tested.
 */
class SosTrigger(
    private val armWindowMs: Long = ARM_WINDOW_MS,
    private val holdMs: Long = HOLD_MS,
) {
    enum class Outcome { None, Armed, Disarmed, Fire }

    private var armedAt = -1L
    private var downAt = -1L
    private var fired = false

    /** True after the first tap, until it expires or the second tap fires. */
    val armed: Boolean get() = armedAt >= 0

    /** 0..1 while the button is held, for the progress ring. 0 when not held. */
    fun holdProgress(now: Long): Float =
        if (downAt < 0) 0f else ((now - downAt).toFloat() / holdMs).coerceIn(0f, 1f)

    /** The finger went down. */
    fun press(now: Long) {
        downAt = now
        fired = false
    }

    /**
     * Call regularly (a frame at a time is fine) while the screen is showing. Fires when the
     * hold completes, and lets an armed button lapse.
     */
    fun tick(now: Long): Outcome {
        if (downAt >= 0 && !fired && now - downAt >= holdMs) {
            fired = true
            armedAt = -1L
            return Outcome.Fire
        }
        if (armedAt >= 0 && downAt < 0 && now - armedAt > armWindowMs) {
            armedAt = -1L
            return Outcome.Disarmed
        }
        return Outcome.None
    }

    /** The finger came up. */
    fun release(now: Long): Outcome {
        val down = downAt
        downAt = -1L
        if (down < 0 || fired) { fired = false; return Outcome.None }
        if (now - down > TAP_MAX_MS) return Outcome.None   // a hold that was let go early
        if (armedAt >= 0 && now - armedAt <= armWindowMs) {
            armedAt = -1L
            return Outcome.Fire
        }
        armedAt = now
        return Outcome.Armed
    }

    /** The screen went away or the call ended: forget everything. */
    fun cancel() {
        armedAt = -1L
        downAt = -1L
        fired = false
    }

    companion object {
        const val ARM_WINDOW_MS = 5_000L
        const val HOLD_MS = 3_000L

        /** A press shorter than this is a tap. */
        const val TAP_MAX_MS = 600L
    }
}
