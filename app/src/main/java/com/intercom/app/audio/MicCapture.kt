package com.intercom.app.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/** Where the voice is picked up from. The order here is the order of quality on a bike. */
enum class MicSource {
    /** Wired boom mic on USB-C or the 3.5 mm jack. Best wind rejection, no Bluetooth involved. */
    Wired,

    /** The phone's own microphone. Keeps the headset on A2DP, so music stays stereo. */
    Phone,

    /** LE Audio headset: mic and high-quality output at the same time on Android 13+. */
    LeAudio,

    /**
     * The Bluetooth headset mic over HFP. This is the one trap in the whole design:
     * opening it drops the headset off A2DP and music collapses to mono telephone
     * quality for the rest of the call. Offered only as an explicit rider choice.
     */
    HeadsetHfp,
}

/**
 * Captures 20 ms mono frames and hands them on, already high-pass filtered and gated.
 *
 * Runs on its own thread at [Process.THREAD_PRIORITY_URGENT_AUDIO]; no audio work ever
 * touches the main thread.
 *
 * [MediaRecorder.AudioSource.VOICE_RECOGNITION] is deliberate. VOICE_COMMUNICATION would
 * be the obvious choice but on most phones it moves the audio mode to MODE_IN_COMMUNICATION
 * and routes to SCO, which is exactly the A2DP-to-HFP switch this design exists to avoid.
 */
class MicCapture(
    private val audioManager: AudioManager,
    private val source: MicSource,
    noiseSuppression: NoiseSuppression = NoiseSuppression.Light,
    /** Capture through native Oboe when it is available; false forces AudioRecord. */
    private val useOboe: Boolean = true,
    /** Told when the stream is being recovered, and when it has been (empty string). */
    private val onStatus: (String) -> Unit = {},
    private val onFrame: (ShortArray) -> Unit,
) {
    companion object {
        private const val TAG = "MicCapture"
        /** Below this RMS the frame is treated as silence and zeroed. */
        private const val NOISE_GATE_RMS = 90.0

        /** No samples for this long on a live stream means it died without telling us. */
        private const val STALL_NANOS = 2_000_000_000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var oboe: OboeEngine? = null

    /** Which capture path is live, for the stats line: "oboe" or "audiorecord". */
    @Volatile var path: String = "none"
        private set
    private val highPass = Biquad.highPass(AudioConstants.SAMPLE_RATE, AudioConstants.TX_HIGHPASS_HZ)

    /** Software spectral suppression, after the phone's own effects have had a go. */
    private val denoiser = SpectralDenoiser(noiseSuppression)

    /** The phone's hardware capture effects, where it has any. */
    private val platformEffects = PlatformAudioEffects()

    /** Which hardware effects are actually running, for the settings screen. */
    val platformEffectsReport: String get() = platformEffects.report

    /** Changed live from settings without restarting the call. */
    var noiseSuppression: NoiseSuppression
        get() = denoiser.strength
        set(value) { denoiser.strength = value }

    @Volatile var lastRms: Double = 0.0
        private set

    fun start(): Boolean {
        if (running) return true
        if (useOboe && NativeBridge.available) {
            if (startOboe()) return true
            Log.w(TAG, "Oboe capture failed to start; falling back to AudioRecord")
        }
        return startAudioRecord()
    }

    /**
     * Opens capture on a native Oboe stream. The preset is VOICE_RECOGNITION for the same
     * reason the AudioRecord path uses it: communication mode would pull a Bluetooth
     * headset off A2DP.
     */
    private fun startOboe(): Boolean {
        val eng = try { OboeEngine() } catch (t: Throwable) {
            Log.w(TAG, "Oboe engine unavailable: ${t.message}")
            return false
        }
        val rc = eng.startInput(pickDevice(source)?.id ?: 0)
        if (rc != 0) {
            Log.w(TAG, "Oboe input start failed, code $rc")
            eng.release()
            return false
        }
        attachEffects(eng.sessionId)
        oboe = eng
        path = "oboe"
        running = true
        highPass.reset()
        denoiser.reset()
        thread = Thread({ loopOboe(eng) }, "mic-capture-oboe").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /** Attaches the phone's own capture effects, then tells the denoiser whether NS ran. */
    private fun attachEffects(sessionId: Int) {
        if (sessionId <= 0) return
        // Take the manufacturer's own capture effects first: they run in the HAL, cost
        // no CPU, and are tuned for this exact microphone. The software denoiser then
        // works on whatever they left behind.
        runCatching {
            platformEffects.attach(
                audioSessionId = sessionId,
                wantNoiseSuppression = denoiser.strength != NoiseSuppression.Off,
            )
        }
        // Stacking the software denoiser at full strength on top of the manufacturer's
        // own suppressor over-processes the voice: the hardware has already taken the
        // steady noise out, so subtracting that much again eats speech.
        denoiser.platformSuppressionActive = platformEffects.report.contains("NS")
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecord(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, AudioConstants.FRAME_BYTES * 4)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConstants.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised")
            rec.release()
            return false
        }

        routeTo(rec, source)

        attachEffects(rec.audioSessionId)

        record = rec
        path = "audiorecord"
        running = true
        highPass.reset()
        denoiser.reset()

        thread = Thread({ loop(rec) }, "mic-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /**
     * Points capture at the device the rider chose. Routing by preferred device keeps the
     * headset on A2DP; only [MicSource.HeadsetHfp] deliberately accepts the SCO switch.
     */
    private fun routeTo(rec: AudioRecord, src: MicSource) {
        val match = pickDevice(src)
        if (match != null) {
            val ok = runCatching { rec.setPreferredDevice(match) }.getOrDefault(false)
            Log.i(TAG, "mic routed to ${match.type} (requested $src, applied=$ok)")
        } else {
            Log.w(TAG, "no input device for $src; using system default")
        }
    }

    /** The input device the rider asked for, if the phone currently has one. */
    private fun pickDevice(src: MicSource): AudioDeviceInfo? {
        val wanted = when (src) {
            MicSource.Wired -> intArrayOf(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
            )
            MicSource.Phone -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
            MicSource.LeAudio -> intArrayOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
            MicSource.HeadsetHfp -> intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }.getOrNull() ?: return null
        for (type in wanted) {
            val d = devices.firstOrNull { it.type == type }
            if (d != null) return d
        }
        return null
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(AudioConstants.FRAME_SAMPLES)
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            running = false
            return
        }

        while (running) {
            var read = 0
            while (read < frame.size && running) {
                val n = rec.read(frame, read, frame.size - read)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord.read error $n")
                        running = false
                    }
                    break
                }
                read += n
            }
            if (!running || read < frame.size) continue

            processFrame(frame)
        }

        runCatching { platformEffects.release() }
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    /**
     * Reads whole frames from the native ring. When the stream is closed underneath us
     * (headset unplugged, route change) it is reopened from here, on this background
     * thread, with backoff, and the rider is told through [onStatus].
     */
    private fun loopOboe(initial: OboeEngine) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val eng = initial
        val frame = ShortArray(AudioConstants.FRAME_SAMPLES)
        val scratch = ShortArray(AudioConstants.FRAME_SAMPLES)
        val backoff = RestartBackoff()
        var filled = 0
        var lastDataAt = System.nanoTime()

        while (running) {
            val stalled = System.nanoTime() - lastDataAt > STALL_NANOS
            if (eng.needsRestart || stalled) {
                if (!recover(eng, backoff)) break
                filled = 0
                lastDataAt = System.nanoTime()
                continue
            }

            val n = eng.readCapture(scratch, AudioConstants.FRAME_SAMPLES - filled)
            if (n <= 0) {
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                continue
            }
            lastDataAt = System.nanoTime()
            System.arraycopy(scratch, 0, frame, filled, n)
            filled += n
            if (filled < AudioConstants.FRAME_SAMPLES) continue
            filled = 0
            backoff.reset()
            processFrame(frame)
        }

        runCatching { platformEffects.release() }
        eng.release()
        oboe = null
    }

    /** Reopens the input stream after an error. False means give up and end capture. */
    private fun recover(eng: OboeEngine, backoff: RestartBackoff): Boolean {
        onStatus("Microphone changed - reconnecting")
        while (running) {
            val wait = backoff.next()
            if (wait == null) {
                Log.e(TAG, "giving up reopening the microphone after ${backoff.attemptsMade} tries")
                onStatus("Microphone lost")
                running = false
                return false
            }
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return false }
            eng.stop()
            val rc = eng.startInput(pickDevice(source)?.id ?: 0)
            if (rc == 0) {
                runCatching { platformEffects.release() }
                attachEffects(eng.sessionId)
                highPass.reset()
                denoiser.reset()
                Log.i(TAG, "microphone stream reopened after ${backoff.attemptsMade} tries")
                onStatus("")
                return true
            }
            Log.w(TAG, "reopen attempt ${backoff.attemptsMade} failed, code $rc")
        }
        return false
    }

    /** The processing every captured frame gets, whichever API it came from. */
    private fun processFrame(frame: ShortArray) {
        // Wind and engine rumble live below 150 Hz; strip it before anything else sees it.
        highPass.processInPlace(frame)

        // Measure the gate on the signal BEFORE suppression. Measuring it after was a
        // real bug: the denoiser pulls quiet speech - consonants, the tails of words -
        // under the threshold, and the gate then replaced them with digital silence.
        // That is what chopped the voice.
        var sum = 0.0
        for (s in frame) sum += (s.toInt() * s.toInt()).toDouble()
        val rawRms = Math.sqrt(sum / frame.size)
        lastRms = rawRms

        val denoising = denoiser.strength != NoiseSuppression.Off
        if (!denoising && rawRms < NOISE_GATE_RMS) {
            // Only crude-gate when nothing better is running. The denoiser's own
            // spectral floor does the same job without a cliff edge.
            for (i in frame.indices) frame[i] = 0
        } else {
            // Take out the steady broadband part - engine, road, wind roar - so the
            // buddy hears the voice rather than the bike.
            denoiser.process(frame)
        }

        onFrame(frame)
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record = null
    }

    /** True when an input of the kind [src] needs is actually present on this phone. */
    fun hasDeviceFor(src: MicSource): Boolean = inputAvailable(audioManager, src)
}

/** True when this phone currently exposes an input device suitable for [src]. */
fun inputAvailable(am: AudioManager, src: MicSource): Boolean {
    val types = when (src) {
        MicSource.Wired -> intArrayOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        MicSource.Phone -> intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        MicSource.LeAudio -> intArrayOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
        MicSource.HeadsetHfp -> intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    }
    val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull()
        ?: return false
    return devices.any { d -> types.any { it == d.type } }
}
