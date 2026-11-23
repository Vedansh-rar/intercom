package com.intercom.app.audio

/**
 * Picks the Light/Medium/Strong preset from GPS speed, for the "Auto" noise setting.
 *
 * Wind and engine noise at the mic grow quickly with speed, so a slow ride wants the gentle
 * preset (which keeps the voice natural) and a motorway ride wants the strong one.
 *
 * Two things keep it from flapping at a boundary:
 * - Hysteresis: to move up past a threshold the speed must exceed it by [HYSTERESIS_KMH],
 *   and to move back down it must fall that far below it.
 * - Dwell: the new preset must be the one wanted continuously for [dwellMs] before it is
 *   applied, so a short burst of speed (an overtake) does not change the sound.
 *
 * The first speed it sees applies at once, with no hysteresis or dwell. With no speed (no
 * fix) it keeps whatever it last chose. The rider's manual choice always wins over it:
 * the caller stops using it as soon as the rider picks a preset themselves.
 */
class AutoNoisePolicy(private val dwellMs: Long = DWELL_MS) {

    private var current: NoiseSuppression? = null
    private var pending: NoiseSuppression? = null
    private var pendingSince = 0L

    /** The preset in force, or null before the first speed. */
    val preset: NoiseSuppression? get() = current

    fun reset() {
        current = null
        pending = null
    }

    /**
     * @param speedKmh the rider's own speed, or null when there is no fresh fix
     * @param nowMs a monotonic clock
     * @return the preset to use now, or null if there has never been a speed
     */
    fun update(speedKmh: Double?, nowMs: Long): NoiseSuppression? {
        if (speedKmh == null || speedKmh.isNaN()) {
            pending = null
            return current
        }
        val cur = current
        if (cur == null) {
            current = presetFor(bandFor(speedKmh, 0, hysteresis = 0.0))
            return current
        }
        val wanted = presetFor(bandFor(speedKmh, PRESETS.indexOf(cur), HYSTERESIS_KMH))
        if (wanted == cur) {
            pending = null
        } else if (wanted != pending) {
            pending = wanted
            pendingSince = nowMs
        } else if (nowMs - pendingSince >= dwellMs) {
            current = wanted
            pending = null
        }
        return current
    }

    companion object {
        /** Speeds at which Medium and Strong begin. */
        const val MEDIUM_FROM_KMH = 25.0
        const val STRONG_FROM_KMH = 60.0
        const val HYSTERESIS_KMH = 2.0
        const val DWELL_MS = 10_000L

        private val PRESETS = listOf(NoiseSuppression.Light, NoiseSuppression.Medium, NoiseSuppression.Strong)
        private val EDGES = doubleArrayOf(MEDIUM_FROM_KMH, STRONG_FROM_KMH)

        private fun presetFor(band: Int) = PRESETS[band]

        /** The band for [speed], given the band we are in now (for the hysteresis). */
        internal fun bandFor(speed: Double, from: Int, hysteresis: Double): Int {
            var band = from
            while (band < EDGES.size && speed >= EDGES[band] + hysteresis) band++
            while (band > 0 && speed < EDGES[band - 1] - hysteresis) band--
            return band
        }
    }
}
