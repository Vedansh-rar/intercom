package com.intercom.app.audio

/**
 * One native Oboe stream, either capture or playback, behind a lock-free ring. Kotlin
 * reads or writes whole frames from its own thread; the native audio callback never calls
 * back into Java.
 *
 * Create one per direction. [release] must only be called after the thread using it has
 * stopped.
 */
class OboeEngine {
    private var handle: Long = NativeBridge.engineCreate()

    /** 0 on success, otherwise an Oboe error code. */
    fun startInput(deviceId: Int): Int =
        NativeBridge.engineStartInput(handle, AudioConstants.SAMPLE_RATE, deviceId, false)

    fun startOutput(): Int = NativeBridge.engineStartOutput(handle, AudioConstants.SAMPLE_RATE)

    fun stop() = NativeBridge.engineStop(handle)

    fun readCapture(out: ShortArray, count: Int): Int = NativeBridge.engineReadCapture(handle, out, count)

    fun writePlayback(pcm: ShortArray, count: Int): Int = NativeBridge.engineWritePlayback(handle, pcm, count)

    /** Samples queued for playback and not yet played. */
    val buffered: Int get() = NativeBridge.engineBuffered(handle)

    val sessionId: Int get() = NativeBridge.engineSessionId(handle)

    val needsRestart: Boolean get() = NativeBridge.engineNeedsRestart(handle)

    val xruns: Int get() = NativeBridge.engineXruns(handle)

    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            NativeBridge.engineStop(h)
            NativeBridge.engineDestroy(h)
        }
    }
}

/**
 * Waits between attempts to reopen an audio stream that was closed underneath us. Short at
 * first, because most route changes settle within a second, then longer so a headset that
 * is genuinely gone does not keep the CPU busy.
 */
class RestartBackoff(
    private val delaysMs: LongArray = longArrayOf(250, 500, 1000, 2000, 4000),
    private val maxAttempts: Int = 8,
) {
    private var attempts = 0

    /** The wait before the next attempt, or null when it is time to give up. */
    fun next(): Long? {
        if (attempts >= maxAttempts) return null
        val d = delaysMs[attempts.coerceAtMost(delaysMs.size - 1)]
        attempts++
        return d
    }

    /** Call after a restart succeeded and the stream has stayed healthy. */
    fun reset() { attempts = 0 }

    val attemptsMade: Int get() = attempts
}
