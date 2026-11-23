package com.intercom.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import kotlin.math.pow

/**
 * Plays the remote rider's voice through the Android mixer as ordinary media.
 *
 * The track is tagged USAGE_MEDIA with CONTENT_TYPE_SPEECH, so it sits next to the music
 * app in the mixer and both reach the headset over A2DP. Nothing here ever touches the
 * communication path, which is what keeps music in stereo for the whole ride.
 *
 * The track is never stopped mid-call. During silence it writes zeroes, because stopping
 * and restarting an A2DP stream makes the headset renegotiate and clips the first word
 * back.
 */
class VoicePlayer(
    source: FrameSource,
    private val beeper: Beeper,
) {
    companion object {
        private const val TAG = "VoicePlayer"

        /** Frames queued ahead of the native stream. 3 x 20 ms = 60 ms of cushion. */
        private const val QUEUE_FRAMES = 3
    }

    /**
     * Where frames come from: the jitter buffer in a two-rider call, the group mixer on a
     * group ride. Switchable because the call decides which at start, after this exists.
     */
    @Volatile var source: FrameSource = source

    /** Play through native Oboe when available; false forces AudioTrack. Set before [start]. */
    @Volatile var preferOboe: Boolean = true

    /** Told when the stream is being recovered, and when it has been (empty string). */
    @Volatile var onStatus: (String) -> Unit = {}

    private var oboe: OboeEngine? = null

    /** Which output path is live, for the stats line: "oboe" or "audiotrack". */
    @Volatile var path: String = "none"
        private set

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** Linear gain on the voice. Raised when the rider chooses not to duck the music. */
    @Volatile var gain: Float = 1.0f

    @Volatile var lastOutputRms: Double = 0.0
        private set

    /**
     * Underruns reported by AudioTrack itself. These mean the app did not feed the track
     * fast enough — a scheduling or buffer-size problem — as opposed to a jitter-buffer
     * underrun, which means the frames had not arrived from the radio yet. Telling the
     * two apart is the difference between tuning the buffer and tuning the link.
     */
    val trackUnderruns: Int
        get() = runCatching { oboe?.xruns ?: track?.underrunCount ?: 0 }.getOrDefault(0)

    fun start(): Boolean {
        if (running) return true
        if (preferOboe && NativeBridge.available) {
            if (startOboe()) return true
            Log.w(TAG, "Oboe playback failed to start; falling back to AudioTrack")
        }
        return startAudioTrack()
    }

    private fun startOboe(): Boolean {
        val eng = try { OboeEngine() } catch (t: Throwable) {
            Log.w(TAG, "Oboe engine unavailable: ${t.message}")
            return false
        }
        val rc = eng.startOutput()
        if (rc != 0) {
            Log.w(TAG, "Oboe output start failed, code $rc")
            eng.release()
            return false
        }
        oboe = eng
        path = "oboe"
        running = true
        thread = Thread({ loopOboe(eng) }, "voice-player-oboe").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun startAudioTrack(): Boolean {

        val minBuf = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, AudioConstants.FRAME_BYTES * 4)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioConstants.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack construction failed", t)
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialised")
            t.release()
            return false
        }

        track = t
        path = "audiotrack"
        running = true
        thread = Thread({ loop(t) }, "voice-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(AudioConstants.FRAME_SAMPLES)
        try {
            t.play()
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack.play failed", e)
            running = false
            return
        }

        while (running) {
            produceFrame(frame)

            // A blocking write paces the loop at exactly one frame per 20 ms.
            val written = t.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write error $written")
                break
            }
        }

        runCatching { t.stop() }
        runCatching { t.release() }
    }

    /** Pulls the next 20 ms from the jitter buffer and applies gain and the mute beep. */
    private fun produceFrame(frame: ShortArray) {
        val got = source.take(frame)
        if (!got) frame.fill(0)

        val g = gain
        if (g != 1.0f) {
            for (i in frame.indices) {
                frame[i] = (frame[i] * g).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }

        // The mute beep is mixed into the same stream, never played on its own track.
        beeper.mixInto(frame)

        var sum = 0.0
        for (s in frame) sum += (s.toInt() * s.toInt()).toDouble()
        lastOutputRms = Math.sqrt(sum / frame.size)
    }

    /**
     * Keeps [QUEUE_FRAMES] frames queued in the native ring, one frame at a time, so the
     * loop is paced by the stream actually consuming audio. As with AudioTrack the stream
     * is never stopped during silence: it is fed zeroes, because stopping and restarting
     * an A2DP stream makes the headset renegotiate and clips the next word.
     *
     * A stream closed underneath us (headset unplugged, route change) is reopened here, on
     * this background thread, with backoff, and the rider is told through [onStatus].
     */
    private fun loopOboe(eng: OboeEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(AudioConstants.FRAME_SAMPLES)
        val backoff = RestartBackoff()
        val limit = AudioConstants.FRAME_SAMPLES * QUEUE_FRAMES

        while (running) {
            if (eng.needsRestart) {
                if (!recover(eng, backoff)) break
                continue
            }
            if (eng.buffered >= limit) {
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                continue
            }
            produceFrame(frame)
            eng.writePlayback(frame, frame.size)
            backoff.reset()
        }

        eng.release()
        oboe = null
    }

    private fun recover(eng: OboeEngine, backoff: RestartBackoff): Boolean {
        onStatus("Audio output changed - reconnecting")
        while (running) {
            val wait = backoff.next()
            if (wait == null) {
                Log.e(TAG, "giving up reopening audio output after ${backoff.attemptsMade} tries")
                onStatus("Audio output lost")
                running = false
                return false
            }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return false }
            eng.stop()
            val rc = eng.startOutput()
            if (rc == 0) {
                Log.i(TAG, "audio output reopened after ${backoff.attemptsMade} tries")
                onStatus("")
                return true
            }
            Log.w(TAG, "reopen output attempt ${backoff.attemptsMade} failed, code $rc")
        }
        return false
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        track = null
    }

    /**
     * Re-opens the track after the system took the headset away, for example for an
     * incoming phone call that moved it to HFP and back.
     */
    fun restart() {
        stop()
        source.reset()
        start()
    }

    /** Sets voice gain from a decibel offset, used by the "do not take focus" mode. */
    fun setGainDb(db: Double) {
        gain = 10.0.pow(db / 20.0).toFloat().coerceIn(0.1f, 4.0f)
    }
}
