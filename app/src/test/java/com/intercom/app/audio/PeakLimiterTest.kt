package com.intercom.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PeakLimiterTest {

    @Test fun `signals under the ceiling pass unchanged`() {
        val lim = PeakLimiter()
        for (x in listOf(0.0, 1000.0, -20000.0, 29000.0, -29000.0)) {
            assertEquals(x, lim.process(x), 1e-9)
        }
        assertEquals(1.0, lim.gain, 0.0)
    }

    @Test fun `no output sample ever exceeds the ceiling`() {
        val lim = PeakLimiter()
        val rnd = java.util.Random(11)
        repeat(100_000) {
            val x = (rnd.nextGaussian() * 40_000.0).coerceIn(-32768.0, 32767.0)
            assertTrue(abs(lim.process(x)) <= lim.ceiling + 1e-6)
        }
    }

    @Test fun `a single peak is caught on that very sample`() {
        val lim = PeakLimiter()
        val out = lim.process(32767.0)
        assertEquals(lim.ceiling, out, 1e-6)
    }

    @Test fun `gain recovers after a peak with a time constant of about 50 ms`() {
        val lim = PeakLimiter()
        lim.process(lim.ceiling * 2)            // gain drops to 0.5
        assertEquals(0.5, lim.gain, 1e-9)
        repeat(AudioConstants.SAMPLE_RATE * 50 / 1000) { lim.process(0.0) }
        // One time constant: 63% of the way back to 1.
        assertEquals(1.0 - 0.5 * Math.exp(-1.0), lim.gain, 0.01)
        repeat(AudioConstants.SAMPLE_RATE / 4) { lim.process(0.0) }
        assertTrue(lim.gain > 0.99)
    }

    @Test fun `reset clears the gain`() {
        val lim = PeakLimiter()
        lim.process(32767.0)
        lim.reset()
        assertEquals(1.0, lim.gain, 0.0)
    }
}
