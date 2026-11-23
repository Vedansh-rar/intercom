package com.intercom.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A peak limiter with instant attack and a smooth release.
 *
 * The output never exceeds [ceiling], whatever goes in: every sample is scaled by a gain
 * that is held at or below `ceiling / |sample|`. The gain then eases back towards 1 with
 * time constant [releaseMs], so a single loud peak does not leave the voice ducked for
 * long. This is what stops a shout, a bump of the mic or a sudden AGC gain from clipping
 * the encoder.
 */
class PeakLimiter(
    sampleRate: Int = AudioConstants.SAMPLE_RATE,
    val ceiling: Double = DEFAULT_CEILING,
    releaseMs: Double = 50.0,
) {
    private val releaseCoef = 1.0 - exp(-1.0 / (releaseMs / 1000.0 * sampleRate))

    /** The current limiter gain, 1.0 when it is not acting. */
    var gain: Double = 1.0
        private set

    fun reset() { gain = 1.0 }

    /** Limits one sample, returning the value to emit. */
    fun process(x: Double): Double {
        val peak = abs(x)
        val needed = if (peak > ceiling) ceiling / peak else 1.0
        gain = if (needed < gain) needed else gain + (1.0 - gain) * releaseCoef
        // The release step can only raise the gain towards 1, so re-apply the bound.
        if (gain > needed) gain = needed
        return x * gain
    }

    companion object {
        /** About -1 dBFS: headroom for the codec's own overshoot. */
        const val DEFAULT_CEILING = 29_204.0
    }
}

/**
 * Slow automatic gain control for the transmitted voice, followed by a [PeakLimiter].
 *
 * It evens out the difference between a rider who sits close to the mic and one whose
 * boom has slipped, without the pumping a fast AGC causes in a noisy helmet:
 *
 * - The gain moves toward the level that would bring the frame to [targetDb], but only
 *   [RISE_DB_PER_SEC] up and [FALL_DB_PER_SEC] down. Louder is corrected faster than
 *   quieter, so a shout is tamed quickly and a quiet patch is not blown up.
 * - Frames below [GATE_DB] are treated as "nobody is talking" and the gain is held, so
 *   silence and low background noise are never ramped up.
 * - The gain is bounded to [MIN_GAIN_DB]..[MAX_GAIN_DB].
 * - Within a frame the gain is interpolated per sample, so there are no steps at frame edges.
 *
 * Not thread safe; it belongs to the capture thread.
 */
class SlowAgc(
    private val sampleRate: Int = AudioConstants.SAMPLE_RATE,
    private val targetDb: Double = TARGET_DB,
) {
    private val limiter = PeakLimiter(sampleRate)

    /** The AGC gain in force, in dB. 0 until it has had speech to learn from. */
    var gainDb: Double = 0.0
        private set

    /** The limiter's current gain (1.0 when idle), for tests and the stats line. */
    val limiterGain: Double get() = limiter.gain

    fun reset() {
        gainDb = 0.0
        limiter.reset()
    }

    /** Processes [frame] in place. */
    fun process(frame: ShortArray, count: Int = frame.size) {
        if (count <= 0) return
        var sum = 0.0
        for (i in 0 until count) {
            val s = frame[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / count)
        val levelDb = if (rms < 1.0) -120.0 else 20.0 * log10(rms / FULL_SCALE)

        val startLinear = dbToLinear(gainDb)
        if (levelDb >= GATE_DB) {
            val frameSec = count.toDouble() / sampleRate
            val wanted = (targetDb - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            gainDb = when {
                wanted > gainDb -> minOf(wanted, gainDb + RISE_DB_PER_SEC * frameSec)
                else -> maxOf(wanted, gainDb - FALL_DB_PER_SEC * frameSec)
            }
        }
        val endLinear = dbToLinear(gainDb)

        for (i in 0 until count) {
            val g = startLinear + (endLinear - startLinear) * (i + 1) / count
            val y = limiter.process(frame[i] * g)
            frame[i] = y.roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    companion object {
        /** Aim for about -22 dBFS RMS: loud enough to carry over wind, with room for peaks. */
        const val TARGET_DB = -22.0

        /**
         * Below -45 dBFS RMS the frame is background, not speech. Wind that gets through
         * the denoiser at a rider's idle sits well under this; speech after denoising is
         * well over it.
         */
        const val GATE_DB = -45.0
        const val MAX_GAIN_DB = 12.0
        const val MIN_GAIN_DB = -6.0
        const val RISE_DB_PER_SEC = 3.0
        const val FALL_DB_PER_SEC = 12.0

        private const val FULL_SCALE = 32768.0

        private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    }
}
