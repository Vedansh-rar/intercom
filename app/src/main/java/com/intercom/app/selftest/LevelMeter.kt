package com.intercom.app.selftest

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The meter on the test-call screen: turns 16-bit samples into a 0..1 bar and keeps a peak
 * hold, so a short word still leaves something to read.
 */
class LevelMeter(private val floorDb: Double = -60.0, private val holdFrames: Int = 15) {

    private var peak = 0f
    private var held = 0
    var maxLevel = 0f
        private set

    /** Level of one frame, 0..1 on a decibel scale from [floorDb] to full scale. */
    fun level(frame: ShortArray, count: Int = frame.size): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return fromRms(sqrt(sum / count))
    }

    fun fromRms(rms: Double): Float {
        if (rms <= 1e-9) return 0f
        val db = 20 * log10(rms)
        return ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
    }

    /** Feeds a frame; returns the level and updates the held peak. */
    fun push(frame: ShortArray, count: Int = frame.size): Float {
        val l = level(frame, count)
        if (l >= peak) { peak = l; held = holdFrames } else if (held > 0) held-- else peak = maxOf(l, peak - 0.02f)
        if (l > maxLevel) maxLevel = l
        return l
    }

    val peakHold: Float get() = peak

    /** True once a frame has clipped: the rider's voice is too hot for the mic. */
    fun clipped(frame: ShortArray, count: Int = frame.size): Boolean {
        for (i in 0 until count) if (abs(frame[i].toInt()) >= 32700) return true
        return false
    }

    /** "Did the microphone hear anything at all?" - the one question the test has to answer. */
    fun heardVoice(threshold: Float = HEARD_LEVEL): Boolean = maxLevel >= threshold

    companion object {
        /** About -42 dBFS: above a quiet room, below speech held a hand from the mic. */
        const val HEARD_LEVEL = 0.3f
    }
}
