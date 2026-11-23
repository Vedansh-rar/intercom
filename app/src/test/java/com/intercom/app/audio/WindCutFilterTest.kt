package com.intercom.app.audio

import com.intercom.app.audio.SignalTools.RATE
import com.intercom.app.audio.SignalTools.dbfs
import com.intercom.app.audio.SignalTools.rms
import com.intercom.app.audio.SignalTools.sineFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindCutFilterTest {

    /** Gain in dB of a filter on a steady sine, measured after the start-up transient. */
    private fun gainDb(filter: WindCutFilter, hz: Double): Double {
        val input = sineFrames(hz, -20.0, 40)
        input.forEach { filter.processInPlace(it) }
        return dbfs(rms(input, skipFrames = 20)) - -20.0
    }

    @Test fun `default is exactly the old fixed 150 Hz second-order high-pass`() {
        val reference = Biquad.highPass(RATE, AudioConstants.TX_HIGHPASS_HZ)
        val filter = WindCutFilter()
        val a = sineFrames(90.0, -15.0, 10) + sineFrames(1000.0, -15.0, 10, startFrame = 10)
        val b = a.map { it.copyOf() }
        a.forEach { filter.processInPlace(it) }
        b.forEach { reference.processInPlace(it) }
        for (i in a.indices) assertArrayEquals("frame $i", b[i], a[i])
    }

    @Test fun `default cutoff is 150 and is a valid setting`() {
        assertEquals(150, WindCutFilter.DEFAULT_HZ)
        assertEquals(150.0, AudioConstants.TX_HIGHPASS_HZ, 0.0)
        assertEquals(150, WindCutFilter().config.cutoffHz)
        assertEquals(false, WindCutFilter().config.steep)
    }

    @Test fun `rumble below the cutoff is cut and speech band is kept`() {
        val f = WindCutFilter().apply { configure(150, false) }
        assertTrue("40 Hz should be cut hard", gainDb(f, 40.0) < -20.0)
        assertTrue("100 Hz should be cut", gainDb(WindCutFilter(), 100.0) < -3.0)
        assertEquals(0.0, gainDb(WindCutFilter(), 1000.0), 0.3)
        assertEquals(0.0, gainDb(WindCutFilter(), 3000.0), 0.3)
    }

    @Test fun `at the cutoff the gain is about minus 3 dB`() {
        assertEquals(-3.0, gainDb(WindCutFilter().apply { configure(200, false) }, 200.0), 0.6)
    }

    @Test fun `a higher cutoff removes more of the same low tone`() {
        val low = gainDb(WindCutFilter().apply { configure(100, false) }, 120.0)
        val high = gainDb(WindCutFilter().apply { configure(300, false) }, 120.0)
        assertTrue("100 Hz cutoff $low vs 300 Hz cutoff $high", high < low - 6.0)
    }

    @Test fun `steep slope cuts the stopband harder than the standard one`() {
        val std = gainDb(WindCutFilter().apply { configure(200, false) }, 80.0)
        val steep = gainDb(WindCutFilter().apply { configure(200, true) }, 80.0)
        assertTrue("standard $std steep $steep", steep < std - 8.0)
        // And the passband is still flat.
        assertEquals(0.0, gainDb(WindCutFilter().apply { configure(200, true) }, 1500.0), 0.3)
    }

    @Test fun `zero turns the filter off and passes samples through untouched`() {
        val f = WindCutFilter().apply { configure(0, false) }
        val a = sineFrames(50.0, -10.0, 5)
        val b = a.map { it.copyOf() }
        a.forEach { f.processInPlace(it) }
        for (i in a.indices) assertArrayEquals(b[i], a[i])
    }

    @Test fun `cutoff is clamped to a sensible range`() {
        assertEquals(0, WindCutFilter.clampCutoff(0))
        assertEquals(0, WindCutFilter.clampCutoff(-5))
        assertEquals(80, WindCutFilter.clampCutoff(10))
        assertEquals(400, WindCutFilter.clampCutoff(5000))
        assertEquals(250, WindCutFilter.clampCutoff(250))
        assertEquals(80, WindCutFilter().apply { configure(30, false) }.config.cutoffHz)
    }

    @Test fun `changing the setting on a live filter takes effect on the next frame`() {
        val f = WindCutFilter()
        val before = gainDb(f, 120.0)
        f.configure(350, true)
        val after = gainDb(f, 120.0)
        assertTrue("before $before after $after", after < before - 10.0)
    }
}
