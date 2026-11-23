package com.intercom.app.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Keeps the sum of several voices inside the 16-bit range without the harsh buzz of hard
 * clipping.
 *
 * Below [KNEE] of full scale the signal passes through untouched, so a single rider is
 * bit-exact. Above it the curve bends smoothly towards full scale with a tanh shoulder
 * that starts with slope 1, so there is no corner at the knee. The output never exceeds
 * +/- 1.0 (for absurdly large inputs it saturates at it, and the mixer clamps to the
 * 16-bit range), so a sum of voices cannot wrap.
 */
object SoftClip {
    const val KNEE = 0.75f

    /** [x] and the result are in units of full scale (1.0 = 32768). */
    fun apply(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val room = 1f - KNEE
        val y = KNEE + room * tanh((a - KNEE) / room)
        return if (x < 0f) -y else y
    }
}
