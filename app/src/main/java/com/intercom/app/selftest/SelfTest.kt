package com.intercom.app.selftest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.MicCapture
import com.intercom.app.util.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the test call is. */
sealed interface SelfTestState {
    data object Idle : SelfTestState
    data class Listening(val level: Float, val peak: Float, val secondsLeft: Int) : SelfTestState
    data object Playing : SelfTestState
    data class Done(val heard: Boolean, val clipped: Boolean) : SelfTestState
    data object NoMic : SelfTestState
}

/**
 * The test call: records a few seconds through the same microphone path the call would use
 * (same source, same noise settings), shows a live meter, then plays the recording back
 * through the headset so the rider hears whether the whole route works. Nothing is sent to
 * anyone and nothing is kept: the recording lives in memory until the test ends.
 *
 * It never runs during a call (the screen refuses to start it), and it is independent of the
 * call pipeline: it does not touch the jitter buffer, the codec, the link or the arbiter.
 */
class SelfTest(
    private val context: Context,
    private val settings: () -> Settings,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _state = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    val state: StateFlow<SelfTestState> = _state.asStateFlow()

    private val meter = LevelMeter()
    private val recorded = ArrayList<ShortArray>()
    private val lock = Any()
    @Volatile private var capture: MicCapture? = null
    @Volatile private var clipped = false
    @Volatile private var stopped = true
    private var startedAt = 0L
    private var playThread: Thread? = null

    /** What the phone is using right now. Safe to call at any time. */
    fun route(): RouteReport {
        val ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map(AudioDeviceInfo::getType)
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(AudioDeviceInfo::getType)
        return AudioRoutes.report(ins, outs)
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!stopped) return true
        val s = settings()
        synchronized(lock) { recorded.clear() }
        clipped = false
        stopped = false
        startedAt = System.currentTimeMillis()
        val m = MicCapture(
            audioManager = audioManager,
            source = s.micSource,
            noiseSuppression = s.noiseSuppression,
            useOboe = !s.legacyAudioPath,
            onFrame = ::onFrame,
        )
        if (!m.start()) {
            stopped = true
            _state.value = SelfTestState.NoMic
            return false
        }
        capture = m
        _state.value = SelfTestState.Listening(0f, 0f, LISTEN_SECONDS)
        return true
    }

    private fun onFrame(frame: ShortArray) {
        if (stopped) return
        val level = meter.push(frame)
        if (meter.clipped(frame)) clipped = true
        synchronized(lock) { recorded += frame.copyOf() }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val left = ((LISTEN_SECONDS * 1000 - elapsedMs + 999) / 1000).toInt().coerceAtLeast(0)
        _state.value = SelfTestState.Listening(level, meter.peakHold, left)
        if (elapsedMs >= LISTEN_SECONDS * 1000L) beginPlayback()
    }

    private fun beginPlayback() {
        if (playThread != null) return
        stopped = true
        val m = capture
        capture = null
        // Stopping the capture from inside its own callback would join its own thread.
        playThread = Thread({
            runCatching { m?.stop() }
            val pcm: List<ShortArray> = synchronized(lock) { recorded.toList() }
            _state.value = SelfTestState.Playing
            val ok = runCatching { play(pcm) }.onFailure { Log.w(TAG, "playback failed: ${it.message}") }.isSuccess
            _state.value = SelfTestState.Done(heard = ok && meter.heardVoice(), clipped = clipped)
            playThread = null
        }, "SelfTestPlay").also { it.start() }
    }

    private fun play(frames: List<ShortArray>) {
        if (frames.isEmpty()) return
        val min = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, AudioConstants.FRAME_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            for (f in frames) {
                if (Thread.currentThread().isInterrupted) break
                track.write(f, 0, f.size)
            }
            // Let the tail drain before the track is released.
            Thread.sleep(200)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Ends the test at once, wherever it is. Safe to call twice. */
    fun stop() {
        stopped = true
        playThread?.interrupt()
        val m = capture
        capture = null
        runCatching { m?.stop() }
        if (_state.value is SelfTestState.Listening || _state.value is SelfTestState.Playing) {
            _state.value = SelfTestState.Idle
        }
        synchronized(lock) { recorded.clear() }
    }

    fun reset() {
        stop()
        _state.value = SelfTestState.Idle
    }

    private companion object {
        const val TAG = "SelfTest"
        const val LISTEN_SECONDS = 4
    }
}
