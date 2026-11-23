package com.intercom.app.audio

import android.util.Log

/**
 * JNI entry points into libintercom_native (libopus plus the Oboe audio engine).
 *
 * Loading can fail on an odd device or ABI; [available] says whether it worked, and every
 * caller has a pure-Kotlin fallback (ADPCM, AudioRecord/AudioTrack) for when it did not.
 * Nothing here may be called when [available] is false.
 */
object NativeBridge {
    private const val TAG = "NativeBridge"

    val available: Boolean = try {
        System.loadLibrary("intercom_native")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "native library not loaded: ${t.message}")
        false
    }

    // ---- Opus
    @JvmStatic external fun opusEncoderCreate(sampleRate: Int, bitrate: Int): Long
    @JvmStatic external fun opusEncoderEncode(handle: Long, pcm: ShortArray, frameSamples: Int, out: ByteArray): Int
    @JvmStatic external fun opusEncoderSetBitrate(handle: Long, bitrate: Int): Boolean
    @JvmStatic external fun opusEncoderDestroy(handle: Long)

    @JvmStatic external fun opusDecoderCreate(sampleRate: Int): Long
    @JvmStatic external fun opusDecoderDecode(handle: Long, payload: ByteArray?, fec: Boolean, out: ShortArray, frameSamples: Int): Int
    @JvmStatic external fun opusDecoderDestroy(handle: Long)

    // ---- Oboe engine (one instance per direction)
    @JvmStatic external fun engineCreate(): Long
    @JvmStatic external fun engineDestroy(handle: Long)

    /** Opens and starts capture. Returns 0 on success, otherwise an Oboe error code. */
    @JvmStatic external fun engineStartInput(handle: Long, sampleRate: Int, deviceId: Int, voiceCommunicationPreset: Boolean): Int

    /** Opens and starts playback. Returns 0 on success, otherwise an Oboe error code. */
    @JvmStatic external fun engineStartOutput(handle: Long, sampleRate: Int): Int
    @JvmStatic external fun engineStop(handle: Long)

    /** Copies up to [count] captured samples into [out]; returns how many were copied. */
    @JvmStatic external fun engineReadCapture(handle: Long, out: ShortArray, count: Int): Int

    /** Queues up to [count] samples for playback; returns how many were accepted. */
    @JvmStatic external fun engineWritePlayback(handle: Long, pcm: ShortArray, count: Int): Int

    /** Samples queued for playback and not yet played. */
    @JvmStatic external fun engineBuffered(handle: Long): Int

    /** Session id of the capture stream, for attaching platform effects; <= 0 if none. */
    @JvmStatic external fun engineSessionId(handle: Long): Int

    /** True after a stream was closed underneath us (headset unplugged, route change). */
    @JvmStatic external fun engineNeedsRestart(handle: Long): Boolean

    /** Over/under-runs seen by the engine's ring buffers and streams since start. */
    @JvmStatic external fun engineXruns(handle: Long): Int
}

/** [OpusEngine] backed by libopus through [NativeBridge]. Separate encoder and decoder. */
class NativeOpusEngine(initialBitrate: Int) : OpusEngine {
    private var enc: Long = NativeBridge.opusEncoderCreate(AudioConstants.SAMPLE_RATE, initialBitrate)
    private var dec: Long = NativeBridge.opusDecoderCreate(AudioConstants.SAMPLE_RATE)

    /** False when libopus could not create its state; the caller must fall back to ADPCM. */
    val usable: Boolean get() = enc != 0L && dec != 0L

    override fun encode(pcm: ShortArray, frameSamples: Int, out: ByteArray): Int {
        val h = enc
        return if (h == 0L) -1 else NativeBridge.opusEncoderEncode(h, pcm, frameSamples, out)
    }

    override fun decode(payload: ByteArray?, fec: Boolean, out: ShortArray, frameSamples: Int): Int {
        val h = dec
        return if (h == 0L) -1 else NativeBridge.opusDecoderDecode(h, payload, fec, out, frameSamples)
    }

    override fun setBitrate(bitsPerSecond: Int): Boolean {
        val h = enc
        return h != 0L && NativeBridge.opusEncoderSetBitrate(h, bitsPerSecond)
    }

    override fun release() {
        val e = enc; val d = dec
        enc = 0L; dec = 0L
        if (e != 0L) NativeBridge.opusEncoderDestroy(e)
        if (d != 0L) NativeBridge.opusDecoderDestroy(d)
    }
}
