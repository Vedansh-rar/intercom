package com.intercom.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenoiseCostMonitorTest {

    private val ms = 1_000_000L

    @Test fun `a cheap denoiser never trips`() {
        val m = DenoiseCostMonitor()
        repeat(1000) { assertFalse(m.record(1 * ms)) }
        assertFalse(m.overBudget)
        assertEquals(1000.0, m.averageMicros, 1.0)
    }

    @Test fun `a denoiser over budget trips once, after a full window`() {
        val m = DenoiseCostMonitor()
        repeat(99) { assertFalse("not before the window is full", m.record(12 * ms)) }
        assertTrue(m.record(12 * ms))
        assertTrue(m.overBudget)
        assertEquals(12_000.0, m.averageMicros, 1.0)
        // Latched: it reports the trip only once.
        assertFalse(m.record(12 * ms))
    }

    @Test fun `one slow start-up frame does not trip it`() {
        val m = DenoiseCostMonitor()
        assertFalse(m.record(40 * ms))              // warm-up outlier
        repeat(99) { assertFalse(m.record(2 * ms)) }
        assertFalse(m.overBudget)
        assertTrue(m.averageMicros < 8_000.0)
    }

    @Test fun `a few scheduling hiccups in a window are tolerated`() {
        val m = DenoiseCostMonitor()
        repeat(100) { i -> assertFalse(m.record(if (i % 20 == 0) 30 * ms else 2 * ms)) }
        assertFalse(m.overBudget)
    }

    @Test fun `exactly on budget is fine, just over trips`() {
        val ok = DenoiseCostMonitor()
        repeat(100) { ok.record(DenoiseCostMonitor.DEFAULT_BUDGET_NANOS) }
        assertFalse(ok.overBudget)
        val over = DenoiseCostMonitor()
        repeat(100) { over.record(DenoiseCostMonitor.DEFAULT_BUDGET_NANOS + 1) }
        assertTrue(over.overBudget)
    }

    @Test fun `each window is judged on its own`() {
        val m = DenoiseCostMonitor()
        repeat(100) { m.record(2 * ms) }
        repeat(100) { m.record(2 * ms) }
        assertFalse(m.overBudget)
        repeat(100) { m.record(20 * ms) }
        assertTrue(m.overBudget)
    }

    @Test fun `reset clears the latch so the AI can be retried`() {
        val m = DenoiseCostMonitor()
        repeat(100) { m.record(20 * ms) }
        assertTrue(m.overBudget)
        m.reset()
        assertFalse(m.overBudget)
        repeat(100) { m.record(1 * ms) }
        assertFalse(m.overBudget)
    }

    @Test fun `the AI engine is not the default and is labelled honestly`() {
        assertEquals(NoiseEngine.Standard, com.intercom.app.util.Settings().noiseEngine)
        assertFalse(com.intercom.app.util.Settings().autoNoise)
        assertFalse(com.intercom.app.util.Settings().agc)
        assertEquals(150, com.intercom.app.util.Settings().windCutHz)
    }
}

class AutoNoisePolicyTest {

    private val dwell = AutoNoisePolicy.DWELL_MS

    @Test fun `no speed and no history gives no preset`() {
        assertNull(AutoNoisePolicy().update(null, 0))
    }

    @Test fun `the first speed applies immediately`() {
        assertEquals(NoiseSuppression.Light, AutoNoisePolicy().update(10.0, 0))
        assertEquals(NoiseSuppression.Medium, AutoNoisePolicy().update(40.0, 0))
        assertEquals(NoiseSuppression.Strong, AutoNoisePolicy().update(90.0, 0))
    }

    @Test fun `a change needs the speed to hold for the dwell time`() {
        val p = AutoNoisePolicy()
        p.update(10.0, 0)
        assertEquals(NoiseSuppression.Light, p.update(80.0, 1_000))
        assertEquals(NoiseSuppression.Light, p.update(80.0, 1_000 + dwell - 1))
        assertEquals(NoiseSuppression.Strong, p.update(80.0, 1_000 + dwell))
    }

    @Test fun `a short burst of speed does not change the preset`() {
        val p = AutoNoisePolicy()
        p.update(10.0, 0)
        p.update(80.0, 1_000)
        p.update(80.0, 5_000)
        p.update(10.0, 6_000)                       // back down: the pending change is dropped
        assertEquals(NoiseSuppression.Light, p.update(80.0, 7_000))
        assertEquals(NoiseSuppression.Light, p.update(80.0, 7_000 + dwell - 1))
    }

    @Test fun `hysteresis keeps it from flapping around a threshold`() {
        val p = AutoNoisePolicy()
        p.update(40.0, 0)                           // Medium
        // Wobbling around 25 km/h, inside the 2 km/h band, never leaves Medium.
        var t = 0L
        for (i in 0 until 100) {
            t += 1_000
            val v = if (i % 2 == 0) 24.0 else 26.0
            assertEquals(NoiseSuppression.Medium, p.update(v, t))
        }
        // Clearly below the band for the dwell time: now it drops.
        p.update(22.0, t + 1_000)
        assertEquals(NoiseSuppression.Light, p.update(22.0, t + 1_000 + dwell))
    }

    @Test fun `going up needs to clear the threshold by the hysteresis`() {
        val p = AutoNoisePolicy()
        p.update(10.0, 0)
        p.update(26.0, 1_000)                       // over 25 but not over 27
        assertEquals(NoiseSuppression.Light, p.update(26.0, 1_000 + dwell * 3))
        p.update(27.5, 100_000)
        assertEquals(NoiseSuppression.Medium, p.update(27.5, 100_000 + dwell))
    }

    @Test fun `it can step across more than one band`() {
        val p = AutoNoisePolicy()
        p.update(10.0, 0)
        p.update(100.0, 1_000)
        assertEquals(NoiseSuppression.Strong, p.update(100.0, 1_000 + dwell))
        p.update(5.0, 50_000)
        assertEquals(NoiseSuppression.Light, p.update(5.0, 50_000 + dwell))
    }

    @Test fun `losing the fix keeps the last preset`() {
        val p = AutoNoisePolicy()
        p.update(90.0, 0)
        assertEquals(NoiseSuppression.Strong, p.update(null, 1_000))
        assertEquals(NoiseSuppression.Strong, p.update(Double.NaN, 2_000))
        assertEquals(NoiseSuppression.Strong, p.preset)
    }

    @Test fun `reset forgets the preset`() {
        val p = AutoNoisePolicy()
        p.update(90.0, 0)
        p.reset()
        assertNull(p.preset)
        assertEquals(NoiseSuppression.Light, p.update(5.0, 1_000))
    }

    @Test fun `the presets increase with speed`() {
        val order = listOf(NoiseSuppression.Light, NoiseSuppression.Medium, NoiseSuppression.Strong)
        val seen = listOf(0.0, 24.0, 26.0, 59.0, 61.0, 130.0).map { AutoNoisePolicy().update(it, 0) }
        assertEquals(seen, seen.sortedBy { order.indexOf(it) })
        assertEquals(setOf(NoiseSuppression.Light, NoiseSuppression.Medium, NoiseSuppression.Strong), seen.toSet())
    }
}
