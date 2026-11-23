package com.intercom.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfortTest {

    @Test fun normal_buttons_never_fall_below_the_touch_minimum() {
        assertEquals(56, Comfort.buttonHeightDp(56, large = false))
        assertEquals(48, Comfort.buttonHeightDp(40, large = false))
        assertEquals(48, Comfort.buttonHeightDp(0, large = false))
    }

    @Test fun glove_mode_makes_buttons_taller_with_a_floor() {
        assertEquals(72, Comfort.buttonHeightDp(52, large = true))   // 52 * 1.3 = 67 -> floor 72
        assertEquals(72, Comfort.buttonHeightDp(56, large = true))
        assertEquals(119, Comfort.buttonHeightDp(92, large = true))
    }

    @Test fun glove_mode_is_always_bigger_than_normal() {
        for (base in listOf(40, 48, 52, 56, 60, 76, 88, 92)) {
            assertTrue(Comfort.buttonHeightDp(base, true) > Comfort.buttonHeightDp(base, false))
        }
    }

    @Test fun high_contrast_text_is_brighter_than_normal_dim_text() {
        val before = Comfort.highContrast
        try {
            Comfort.highContrast = false
            val normal = Brand.TextDim
            Comfort.highContrast = true
            val bright = Brand.TextDim
            assertTrue(luminance(bright) > luminance(normal))
            // WCAG AAA (7:1) against the black background used in this mode.
            assertTrue(contrast(bright, Brand.Background) >= 7.0)
        } finally {
            Comfort.highContrast = before
        }
    }

    @Test fun normal_dim_text_still_meets_aa_on_the_background() {
        val before = Comfort.highContrast
        try {
            Comfort.highContrast = false
            assertTrue(contrast(Brand.TextDim, Brand.Background) >= 4.5)
        } finally {
            Comfort.highContrast = before
        }
    }

    private fun lin(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(c: androidx.compose.ui.graphics.Color): Double =
        0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val l1 = maxOf(luminance(a), luminance(b))
        val l2 = minOf(luminance(a), luminance(b))
        return (l1 + 0.05) / (l2 + 0.05)
    }
}
