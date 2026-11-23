package com.intercom.app.audio

import kotlin.math.PI
import kotlin.math.sin

/** The sounds the app makes about the call itself, as opposed to voice. */
enum class AlertKind { Sos, LinkLost, LinkRestored }

/** One steady tone ([hz] 0 = silence) of [ms] milliseconds. */
data class ToneSegment(val hz: Double, val ms: Int)

object AlertPatterns {
    /** A loud two-tone siren, nothing like the 100 ms beep or the link tones. About 5 seconds. */
    val SOS: List<ToneSegment> = buildList {
        repeat(2) {
            repeat(5) {
                add(ToneSegment(960.0, 250))
                add(ToneSegment(640.0, 250))
            }
            add(ToneSegment(0.0, 300))
        }
    }

    /** Two falling notes. */
    val LINK_LOST = listOf(ToneSegment(660.0, 130), ToneSegment(440.0, 220))

    /** Three rising notes. */
    val LINK_RESTORED = listOf(ToneSegment(440.0, 90), ToneSegment(660.0, 90), ToneSegment(880.0, 160))

    fun of(kind: AlertKind): List<ToneSegment> = when (kind) {
        AlertKind.Sos -> SOS
        AlertKind.LinkLost -> LINK_LOST
        AlertKind.LinkRestored -> LINK_RESTORED
    }

    fun amplitude(kind: AlertKind): Double = when (kind) {
        AlertKind.Sos -> 0.85
        else -> 0.35
    }

    fun totalMs(kind: AlertKind): Int = of(kind).sumOf { it.ms }
}

/**
 * Plays one alert at a time into the same output stream as the voice, so it reaches the
 * headset the way everything else does and opens no second audio path. Local only: never
 * sent to anyone. A new alert replaces the one playing, and an SOS is never cut off by a
 * quieter one.
 *
 * Alerts are mixed after the voice is silenced for deafen, on purpose: a deafened rider
 * must still hear an SOS.
 */
class AlertSequencer {
    private val fadeSamples = AudioConstants.SAMPLE_RATE * 5 / 1000

    @Volatile private var segments: List<ToneSegment> = emptyList()
    @Volatile private var amp = 0.0
    @Volatile private var kind: AlertKind? = null
    private var segIndex = 0
    private var segPos = 0
    private var phase = 0.0

    val isPlaying: Boolean get() = kind != null
    val current: AlertKind? get() = kind

    @Synchronized
    fun play(k: AlertKind) {
        if (kind == AlertKind.Sos && k != AlertKind.Sos) return
        segments = AlertPatterns.of(k)
        amp = AlertPatterns.amplitude(k)
        segIndex = 0
        segPos = 0
        phase = 0.0
        kind = k
    }

    @Synchronized
    fun stop() { kind = null }

    /** Mixes the tone into [buf] in place. Called on the playback thread. */
    @Synchronized
    fun mixInto(buf: ShortArray) {
        if (kind == null) return
        val rate = AudioConstants.SAMPLE_RATE
        for (i in buf.indices) {
            if (segIndex >= segments.size) { kind = null; return }
            val seg = segments[segIndex]
            val len = seg.ms * rate / 1000
            if (seg.hz > 0) {
                val edge = minOf(fadeSamples, len / 2).coerceAtLeast(1)
                val env = when {
                    segPos < edge -> segPos.toDouble() / edge
                    len - segPos < edge -> (len - segPos).toDouble() / edge
                    else -> 1.0
                }
                val tone = sin(phase) * amp * env * Short.MAX_VALUE
                phase += 2.0 * PI * seg.hz / rate
                val mixed = buf[i] + tone
                buf[i] = mixed.coerceIn(-32768.0, 32767.0).toInt().toShort()
            }
            segPos++
            if (segPos >= len) { segIndex++; segPos = 0 }
        }
    }
}
