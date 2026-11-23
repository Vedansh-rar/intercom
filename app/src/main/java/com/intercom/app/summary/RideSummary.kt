package com.intercom.app.summary

/** One reading of the link, taken every few seconds during a call. */
data class LinkSample(
    val atMs: Long,
    val connected: Boolean,
    val rttMs: Int,
    val lossPercent: Double,
    val transport: String?,
) {
    /** 0 (nothing gets through) to 1 (clean). */
    val quality: Float get() = LinkScore.score(connected, rttMs, lossPercent)
}

/** The phones moved the call from one radio to another. */
data class TransportSwitch(val atMs: Long, val from: String?, val to: String)

/** What a finished call looked like, kept in memory only. Holds no positions and no names. */
data class RideSummary(
    val durationMs: Long,
    val samples: List<LinkSample>,
    val switches: List<TransportSwitch>,
    val linkLosses: Int,
    val downMs: Long,
    val avgRttMs: Int?,
    val worstRttMs: Int?,
    val worstLossPercent: Double,
    val riders: Int,
    val group: Boolean,
) {
    val connectedPercent: Int
        get() = if (durationMs <= 0) 100 else (100 - (downMs * 100 / durationMs)).toInt().coerceIn(0, 100)
}

object LinkScore {
    const val GOOD_RTT_MS = 120
    const val BAD_RTT_MS = 600
    const val BAD_LOSS_PERCENT = 20.0

    /** A single number for the graph: delay and lost frames both pull it down, a dropped link is zero. */
    fun score(connected: Boolean, rttMs: Int, lossPercent: Double): Float {
        if (!connected) return 0f
        val lossPart = (1.0 - (lossPercent / BAD_LOSS_PERCENT)).coerceIn(0.0, 1.0)
        val rttPart = if (rttMs < 0) 1.0
        else (1.0 - (rttMs - GOOD_RTT_MS).toDouble() / (BAD_RTT_MS - GOOD_RTT_MS)).coerceIn(0.0, 1.0)
        // Loss is what a rider hears as gaps; delay is only felt in conversation. Weight it so.
        return (0.65 * lossPart + 0.35 * rttPart).toFloat().coerceIn(0f, 1f)
    }
}
