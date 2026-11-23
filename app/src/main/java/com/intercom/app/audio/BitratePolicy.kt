package com.intercom.app.audio

/**
 * Picks the Opus bitrate from how the link is behaving. Pure Kotlin so the rules can be
 * tested directly.
 *
 * Down is quick and up is slow. A rider on a noisy radio link gains more from a lower
 * rate (smaller packets, fewer drops) than from the last few kbps of fidelity, but a
 * brief glitch should not strip quality, and the rate should not bounce around, so:
 *
 *  - [downAfter] consecutive bad samples step one rung down the [ladder];
 *  - [upAfter] consecutive good samples step one rung up;
 *  - a sample that is neither clearly bad nor clearly good changes nothing and resets
 *    neither run, which gives the hysteresis band between the two thresholds.
 *
 * One sample is taken per call to [onSample], which the caller makes once a second.
 */
class BitratePolicy(
    private val ladder: IntArray = intArrayOf(24_000, 16_000, 12_000),
    private val badLossPercent: Double = 4.0,
    private val badRttMs: Int = 400,
    private val goodLossPercent: Double = 1.0,
    private val goodRttMs: Int = 200,
    private val downAfter: Int = 2,
    private val upAfter: Int = 15,
) {
    init { require(ladder.isNotEmpty() && ladder.toList().zipWithNext().all { (a, b) -> a > b } ) {
        "ladder must be strictly descending"
    } }

    private var rung = 0
    private var badRun = 0
    private var goodRun = 0

    /** The bitrate to use now. */
    val bitrate: Int get() = ladder[rung]

    /**
     * Feeds one reading. [rttMs] is negative when unknown, which counts as neither good
     * nor bad on its own; loss alone can still move the rate.
     * Returns the bitrate to use after this sample.
     */
    fun onSample(lossPercent: Double, rttMs: Int): Int {
        val rttKnown = rttMs >= 0
        val bad = lossPercent >= badLossPercent || (rttKnown && rttMs >= badRttMs)
        val good = lossPercent <= goodLossPercent && (!rttKnown || rttMs <= goodRttMs)

        when {
            bad -> {
                goodRun = 0
                badRun++
                if (badRun >= downAfter && rung < ladder.size - 1) {
                    rung++
                    badRun = 0
                }
            }
            good -> {
                badRun = 0
                goodRun++
                if (goodRun >= upAfter && rung > 0) {
                    rung--
                    goodRun = 0
                }
            }
            else -> Unit // in the band between: hold, and keep both runs as they are
        }
        return bitrate
    }

    fun reset() {
        rung = 0
        badRun = 0
        goodRun = 0
    }
}
