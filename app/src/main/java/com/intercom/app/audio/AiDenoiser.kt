package com.intercom.app.audio

import android.util.Log

/** Which noise reduction does the work. The Light/Medium/Strong strength applies to [Standard] only. */
enum class NoiseEngine(val label: String) {
    /** The built-in spectral denoiser: cheap, tunable, the default. */
    Standard("Standard"),

    /** RNNoise, a small neural network. Handles gusty and non-steady noise better; costs more CPU. */
    Ai("AI noise reduction"),
}

/**
 * RNNoise (Xiph.Org, BSD-3-Clause) behind the native bridge. It works on this app's 16 kHz
 * frames; the native wrapper resamples to the 48 kHz the network needs and back, which adds
 * about 11 ms of delay.
 *
 * Not thread safe: create it, use it and release it on the capture thread.
 */
class AiDenoiser private constructor(private var handle: Long) {

    /** Denoises one frame (a multiple of 160 samples) in place. False leaves it untouched. */
    fun process(frame: ShortArray): Boolean =
        handle != 0L && NativeBridge.rnnoiseProcess(handle, frame, frame.size)

    fun reset() {
        if (handle != 0L) NativeBridge.rnnoiseReset(handle)
    }

    fun release() {
        if (handle != 0L) NativeBridge.rnnoiseDestroy(handle)
        handle = 0L
    }

    companion object {
        private const val TAG = "AiDenoiser"

        /** Null when the native library did not load or the model could not be set up. */
        fun create(): AiDenoiser? {
            if (!NativeBridge.available) return null
            val h = try { NativeBridge.rnnoiseCreate() } catch (t: Throwable) {
                Log.w(TAG, "RNNoise unavailable: ${t.message}")
                0L
            }
            return if (h == 0L) null else AiDenoiser(h)
        }
    }
}

/**
 * Watches how long the AI denoiser takes per frame and says when it is too much for this
 * phone.
 *
 * A frame is 20 ms of audio, so a denoiser that averages more than [budgetNanos] (40% of
 * that by default) is leaving too little of one core for everything else the capture
 * thread and the rest of the phone have to do, and it risks missing deadlines, which the
 * rider hears as dropouts. Averaging over [window] frames means a single scheduling hiccup
 * or the first-call warm-up does not trip it.
 *
 * Pure logic, so it is unit-tested without a phone.
 */
class DenoiseCostMonitor(
    private val budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    private val window: Int = DEFAULT_WINDOW,
) {
    private var sum = 0L
    private var count = 0

    /** Average of the last completed window, in microseconds (0 before the first). */
    @Volatile var averageMicros: Double = 0.0
        private set

    /** True once a window has averaged over budget. Latched until [reset]. */
    @Volatile var overBudget: Boolean = false
        private set

    fun reset() {
        sum = 0L
        count = 0
        overBudget = false
    }

    /** Records one frame's time. Returns true on the call that trips the budget, once. */
    fun record(nanos: Long): Boolean {
        if (overBudget) return false
        sum += nanos
        count++
        if (count < window) return false
        val avg = sum.toDouble() / count
        averageMicros = avg / 1000.0
        sum = 0L
        count = 0
        if (avg > budgetNanos) {
            overBudget = true
            return true
        }
        return false
    }

    companion object {
        /** 8 ms per 20 ms frame. */
        const val DEFAULT_BUDGET_NANOS = 8_000_000L
        const val DEFAULT_WINDOW = 100
    }
}
