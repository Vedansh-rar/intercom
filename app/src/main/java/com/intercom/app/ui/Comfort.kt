package com.intercom.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Riding comfort settings: high contrast for sunlight, bigger buttons for gloves.
 *
 * Held as snapshot state rather than passed down as a parameter: [Brand]'s colours are read
 * in hundreds of places, and threading a flag through all of them would touch every screen.
 * Because the getters in [Brand] read this state while composing, flipping it recomposes
 * exactly the screens that are showing.
 */
object Comfort {
    var highContrast: Boolean by mutableStateOf(false)
    var largeButtons: Boolean by mutableStateOf(false)

    /** The smallest touch target Android's accessibility guidance allows. */
    const val MIN_TOUCH_DP = 48

    /** The tallest-ever floor for a glove-friendly button. */
    const val GLOVE_MIN_DP = 72

    fun apply(highContrast: Boolean, largeButtons: Boolean) {
        this.highContrast = highContrast
        this.largeButtons = largeButtons
    }

    /**
     * Height for a button designed at [baseDp]: never below 48 dp, and in glove mode about a
     * third bigger with a 72 dp floor. Pure ints so the rule can be unit-tested.
     */
    fun buttonHeightDp(baseDp: Int, large: Boolean): Int =
        if (large) maxOf((baseDp * 1.3f).toInt(), GLOVE_MIN_DP) else maxOf(baseDp, MIN_TOUCH_DP)

    fun buttonHeight(base: Dp): Dp = buttonHeightDp(base.value.toInt(), largeButtons).dp
}
