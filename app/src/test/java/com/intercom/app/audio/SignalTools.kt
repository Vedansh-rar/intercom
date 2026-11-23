package com.intercom.app.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic signals and measurements shared by the filter, AGC and limiter tests. */
internal object SignalTools {
    const val RATE = AudioConstants.SAMPLE_RATE
    const val FRAME = AudioConstants.FRAME_SAMPLES

    /** [frames] frames of a sine at [hz] whose RMS is [rmsDbfs] below full scale. */
    fun sineFrames(hz: Double, rmsDbfs: Double, frames: Int, startFrame: Int = 0): List<ShortArray> {
        val amp = Math.pow(10.0, rmsDbfs / 20.0) * 32768.0 * sqrt(2.0)
        return List(frames) { f ->
            ShortArray(FRAME) { i ->
                val n = (startFrame + f) * FRAME + i
                (amp * sin(2.0 * PI * hz * n / RATE)).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    fun rms(frames: List<ShortArray>, skipFrames: Int = 0): Double {
        var sum = 0.0
        var n = 0
        for (f in frames.drop(skipFrames)) for (s in f) { sum += s.toDouble() * s; n++ }
        return sqrt(sum / n)
    }

    fun dbfs(rms: Double): Double = 20.0 * log10(rms / 32768.0)

    fun peak(frames: List<ShortArray>): Int = frames.maxOf { f -> f.maxOf { kotlin.math.abs(it.toInt()) } }
}
