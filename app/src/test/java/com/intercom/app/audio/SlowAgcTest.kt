package com.intercom.app.audio

import com.intercom.app.audio.SignalTools.FRAME
import com.intercom.app.audio.SignalTools.dbfs
import com.intercom.app.audio.SignalTools.peak
import com.intercom.app.audio.SignalTools.rms
import com.intercom.app.audio.SignalTools.sineFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SlowAgcTest {

    private fun run(agc: SlowAgc, frames: List<ShortArray>) = frames.forEach { agc.process(it) }

    private val framesPerSecond = 1000 / AudioConstants.FRAME_MS

    @Test fun `silence stays silence and the gain does not move`() {
        val agc = SlowAgc()
        val frames = List(500) { ShortArray(FRAME) }
        run(agc, frames)
        assertEquals(0.0, agc.gainDb, 0.0)
        assertEquals(0, peak(frames))
    }

    @Test fun `quiet background noise below the gate is never amplified`() {
        val agc = SlowAgc()
        val rnd = Random(7)
        // About -55 dBFS RMS: well under the -45 dB gate.
        val noise = List(1000) { ShortArray(FRAME) { (rnd.nextInt(-80, 80)).toShort() } }
        val copy = noise.map { it.copyOf() }
        run(agc, noise)
        assertEquals("gain held at unity", 0.0, agc.gainDb, 0.0)
        for (i in noise.indices) assertArrayEquals("frame $i untouched", copy[i], noise[i])
    }

    @Test fun `a quiet steady tone is raised slowly, not at once`() {
        val agc = SlowAgc()
        val tone = sineFrames(500.0, -35.0, framesPerSecond * 10)
        // One second in: no more than the rise rate allows.
        run(agc, tone.take(framesPerSecond))
        assertTrue("after 1 s the gain is ${agc.gainDb}", agc.gainDb in 2.5..3.1)
        run(agc, tone.drop(framesPerSecond))
        // -35 dBFS wants +13 dB; the cap is +12.
        assertEquals(SlowAgc.MAX_GAIN_DB, agc.gainDb, 0.01)
    }

    @Test fun `a steady tone converges to the target level`() {
        val agc = SlowAgc()
        val tone = sineFrames(500.0, -30.0, framesPerSecond * 12)
        run(agc, tone)
        // Wanted gain is +8 dB, reached after about 2.7 s at 3 dB/s.
        assertEquals(8.0, agc.gainDb, 0.2)
        assertEquals(SlowAgc.TARGET_DB, dbfs(rms(tone, skipFrames = framesPerSecond * 6)), 0.5)
    }

    @Test fun `a tone already at the target is left alone`() {
        val agc = SlowAgc()
        val tone = sineFrames(500.0, SlowAgc.TARGET_DB, framesPerSecond * 5)
        run(agc, tone)
        assertEquals(0.0, agc.gainDb, 0.1)
    }

    @Test fun `a too loud tone is brought down faster than a quiet one is raised`() {
        val up = SlowAgc()
        val down = SlowAgc()
        run(up, sineFrames(500.0, -34.0, framesPerSecond))
        run(down, sineFrames(500.0, -10.0, framesPerSecond))
        assertTrue("up ${up.gainDb}, down ${down.gainDb}", -down.gainDb > up.gainDb)
        // -10 dBFS wants -12 dB but the floor is -6; 12 dB/s gets there inside a second.
        assertEquals(SlowAgc.MIN_GAIN_DB, down.gainDb, 0.01)
    }

    @Test fun `gain is always within its bounds`() {
        val agc = SlowAgc()
        val rnd = Random(3)
        repeat(2000) {
            val level = rnd.nextDouble(-60.0, -3.0)
            agc.process(sineFrames(300.0 + rnd.nextInt(2000), level, 1)[0])
            assertTrue(agc.gainDb in SlowAgc.MIN_GAIN_DB..SlowAgc.MAX_GAIN_DB)
        }
    }

    @Test fun `silence between words holds the gain`() {
        val agc = SlowAgc()
        run(agc, sineFrames(500.0, -30.0, framesPerSecond * 5))
        val learned = agc.gainDb
        run(agc, List(framesPerSecond * 5) { ShortArray(FRAME) })
        assertEquals(learned, agc.gainDb, 0.0)
    }

    @Test fun `clipping input never exceeds the limiter ceiling even at maximum gain`() {
        val agc = SlowAgc()
        // Teach it +12 dB on a quiet tone, then slam it with full scale.
        run(agc, sineFrames(500.0, -40.0, framesPerSecond * 10))
        assertEquals(SlowAgc.MAX_GAIN_DB, agc.gainDb, 0.01)
        val loud = List(50) { ShortArray(FRAME) { i -> if ((i / 20) % 2 == 0) 32767 else -32768 } }
        run(agc, loud)
        val ceiling = PeakLimiter.DEFAULT_CEILING.toInt()
        assertTrue("peak ${peak(loud)} over $ceiling", peak(loud) <= ceiling)
    }

    @Test fun `the output of a hot sine is bounded by the ceiling`() {
        val agc = SlowAgc()
        val hot = sineFrames(400.0, -1.0, 200)
        run(agc, hot)
        assertTrue(peak(hot) <= PeakLimiter.DEFAULT_CEILING.toInt())
    }

    @Test fun `reset returns to unity`() {
        val agc = SlowAgc()
        run(agc, sineFrames(500.0, -30.0, framesPerSecond * 5))
        assertTrue(agc.gainDb > 1.0)
        agc.reset()
        assertEquals(0.0, agc.gainDb, 0.0)
        assertEquals(1.0, agc.limiterGain, 0.0)
    }

    @Test fun `there are no steps at frame edges while the gain is moving`() {
        val agc = SlowAgc()
        val frames = sineFrames(200.0, -30.0, 30)
        val raw = frames.map { it.copyOf() }
        run(agc, frames)
        // A 200 Hz tone changes by a small amount sample to sample; a gain step at a frame
        // boundary would show as a jump much larger than the neighbours.
        val maxInFrame = frames.maxOf { f -> (1 until FRAME).maxOf { kotlin.math.abs(f[it] - f[it - 1]) } }
        for (i in 1 until frames.size) {
            val jump = kotlin.math.abs(frames[i][0] - frames[i - 1][FRAME - 1])
            assertTrue("jump $jump at frame $i vs in-frame $maxInFrame", jump <= maxInFrame * 1.5 + 2)
        }
        assertTrue(raw.isNotEmpty())
    }
}
