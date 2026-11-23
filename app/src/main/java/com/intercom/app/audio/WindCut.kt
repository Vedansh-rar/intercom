package com.intercom.app.audio

/**
 * The transmit high-pass that strips wind buffeting and engine rumble, with an adjustable
 * cutoff.
 *
 * The default (150 Hz, second order) is exactly the filter the app has always used, so a
 * rider who never opens this setting gets bit-identical audio to before. Two things can be
 * changed: the cutoff, and the slope. [steep] cascades two Butterworth sections into a
 * fourth-order filter (24 dB per octave instead of 12), which removes more of a gusty
 * helmet's low-frequency thump at the price of a little more phase shift around the cutoff.
 *
 * [configure] may be called from any thread; the new filter is built and swapped in on the
 * thread that calls [processInPlace], which is the capture thread.
 */
class WindCutFilter(private val sampleRate: Int = AudioConstants.SAMPLE_RATE) {

    /** A requested setting. [cutoffHz] of 0 means the filter is off. */
    data class Config(val cutoffHz: Int, val steep: Boolean)

    @Volatile private var requested = Config(DEFAULT_HZ, false)
    private var applied: Config? = null
    private var first: Biquad? = null
    private var second: Biquad? = null

    /** The setting in force, after clamping. */
    val config: Config get() = requested

    fun configure(cutoffHz: Int, steep: Boolean) {
        requested = Config(clampCutoff(cutoffHz), steep)
    }

    fun reset() {
        first?.reset()
        second?.reset()
    }

    fun processInPlace(frame: ShortArray) {
        val want = requested
        if (want != applied) rebuild(want)
        val a = first ?: return
        a.processInPlace(frame)
        second?.processInPlace(frame)
    }

    private fun rebuild(want: Config) {
        applied = want
        if (want.cutoffHz == 0) {
            first = null
            second = null
            return
        }
        val hz = want.cutoffHz.toDouble()
        if (want.steep) {
            // The two section Qs of a fourth-order Butterworth.
            first = Biquad.highPass(sampleRate, hz, Q_4TH_A)
            second = Biquad.highPass(sampleRate, hz, Q_4TH_B)
        } else {
            first = Biquad.highPass(sampleRate, hz)
            second = null
        }
    }

    companion object {
        const val DEFAULT_HZ = 150
        const val MIN_HZ = 80
        const val MAX_HZ = 400
        const val STEP_HZ = 10

        private const val Q_4TH_A = 0.5411961001461970
        private const val Q_4TH_B = 1.3065629648763766

        /** 0 stays 0 (off); anything else is held to the range that makes sense for speech. */
        fun clampCutoff(hz: Int): Int = if (hz <= 0) 0 else hz.coerceIn(MIN_HZ, MAX_HZ)
    }
}
