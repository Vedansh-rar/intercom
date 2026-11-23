package com.intercom.app.group

import com.intercom.app.audio.SoftClip
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftClipTest {
    @Test fun belowTheKneeTheSignalIsUntouched() {
        for (x in listOf(0f, 0.1f, -0.5f, 0.75f, -0.75f)) assertEquals(x, SoftClip.apply(x), 0f)
    }

    @Test fun staysBelowFullScaleForAnyRealisticSum() {
        // Three voices at up to double gain can sum to about 6x full scale.
        for (x in listOf(0.8f, 1f, 1.5f, 2f)) {
            val y = SoftClip.apply(x)
            assertTrue("$x -> $y", y < 1f && y > SoftClip.KNEE)
        }
    }

    @Test fun neverExceedsFullScaleHoweverLoud() {
        for (x in listOf(3f, 6f, 10f, 1000f, 1e9f)) {
            val y = SoftClip.apply(x)
            assertTrue("$x -> $y", y <= 1f && y > SoftClip.KNEE)
            assertTrue(-y >= -1f)
        }
    }

    @Test fun isSymmetric() {
        for (x in listOf(0.3f, 0.9f, 2f)) assertEquals(-SoftClip.apply(x), SoftClip.apply(-x), 0f)
    }

    @Test fun isMonotonic() {
        var prev = -2f
        var x = -3f
        while (x <= 3f) {
            val y = SoftClip.apply(x)
            assertTrue("not monotonic at $x", y >= prev)
            prev = y
            x += 0.01f
        }
    }

    @Test fun hasNoCornerAtTheKnee() {
        val eps = 0.001f
        val slope = (SoftClip.apply(SoftClip.KNEE + eps) - SoftClip.apply(SoftClip.KNEE)) / eps
        assertTrue("slope $slope", abs(slope - 1f) < 0.01f)
    }

    @Test fun twoLoudVoicesStayInsideSixteenBits() {
        val sum = (30000f + 30000f) / 32768f
        val out = SoftClip.apply(sum) * 32768f
        assertTrue(out < 32767f)
        assertTrue(out > SoftClip.KNEE * 32768f)
    }
}
