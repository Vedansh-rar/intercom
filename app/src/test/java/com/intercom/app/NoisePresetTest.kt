package com.intercom.app

import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.audio.SpectralDenoiser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The README used to say the Light/Medium/Strong selector "sticks on one preset". These
 * tests pin down the contract the settings screen relies on: every preset resolves to
 * different denoiser parameters, in a strictly increasing order of strength, whether or
 * not the phone's hardware suppressor is also running, and changing the preset on a live
 * denoiser takes effect on the next frame.
 */
class NoisePresetTest {

    private val order = listOf(
        NoiseSuppression.Light,
        NoiseSuppression.Medium,
        NoiseSuppression.Strong,
    )

    @Test
    fun `every preset resolves to distinct parameters`() {
        for (platform in listOf(false, true)) {
            val params = NoiseSuppression.entries.map { it.params(platform) }
            assertEquals("platform=$platform", params.size, params.toSet().size)
        }
    }

    @Test
    fun `stronger presets subtract more and floor lower`() {
        for (platform in listOf(false, true)) {
            for ((a, b) in order.zipWithNext()) {
                val pa = a.params(platform)
                val pb = b.params(platform)
                assertTrue("$a < $b over-subtract (platform=$platform)", pa.overSubtract < pb.overSubtract)
                assertTrue("$a > $b gain floor (platform=$platform)", pa.gainFloor > pb.gainFloor)
            }
        }
    }

    @Test
    fun `off does nothing in either mode`() {
        for (platform in listOf(false, true)) {
            val p = NoiseSuppression.Off.params(platform)
            assertEquals(0.0, p.overSubtract, 0.0)
            assertEquals(1.0, p.gainFloor, 0.0)
        }
    }

    @Test
    fun `hardware suppression eases depth without reordering presets`() {
        for (ns in order) {
            assertTrue(ns.params(true).overSubtract < ns.params(false).overSubtract)
        }
    }

    @Test
    fun `changing strength on a live denoiser changes its parameters immediately`() {
        val d = SpectralDenoiser(NoiseSuppression.Light)
        val seen = mutableListOf<Double>()
        for (ns in NoiseSuppression.entries) {
            d.strength = ns
            seen += d.effectiveParams.overSubtract
        }
        assertEquals(NoiseSuppression.entries.size, seen.toSet().size)
    }

    @Test
    fun `stronger presets leave less steady noise in the output`() {
        fun residual(ns: NoiseSuppression): Double {
            val rng = Random(7)
            val d = SpectralDenoiser(ns)
            var energy = 0.0
            var count = 0
            repeat(150) { i ->
                val frame = ShortArray(AudioConstants.FRAME_SAMPLES) {
                    rng.nextInt(-2000, 2001).toShort()
                }
                d.process(frame)
                if (i >= 100) { // after the noise estimate has settled
                    for (s in frame) energy += s.toDouble() * s.toDouble()
                    count += frame.size
                }
            }
            return sqrt(energy / count)
        }

        val results = NoiseSuppression.entries.map { residual(it) }
        for ((a, b) in results.zipWithNext()) {
            assertTrue("residual should fall as strength rises: $results", b < a)
        }
    }

    @Test
    fun `coming back from Off does not replay stale audio`() {
        val d = SpectralDenoiser(NoiseSuppression.Strong)
        val rng = Random(3)
        repeat(30) {
            d.process(ShortArray(AudioConstants.FRAME_SAMPLES) { rng.nextInt(-3000, 3001).toShort() })
        }
        d.strength = NoiseSuppression.Off
        d.process(ShortArray(AudioConstants.FRAME_SAMPLES) { 1000 })
        d.strength = NoiseSuppression.Strong
        val silence = ShortArray(AudioConstants.FRAME_SAMPLES)
        d.process(silence)
        val peak = silence.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("first frame after re-enabling should be quiet, was $peak", peak < 500)
    }
}
