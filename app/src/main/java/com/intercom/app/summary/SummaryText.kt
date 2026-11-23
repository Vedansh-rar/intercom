package com.intercom.app.summary

/**
 * Number formatting for the summary screen. Unit-free on purpose ("1:05:03"): it reads the
 * same in every language and needs no translated "hours".
 */
object SummaryText {

    /** 65 s -> "1:05", 3 723 s -> "1:02:03". Negative input is treated as zero. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * The graph's points as fractions of the plot: x is time through the ride (0..1), y is
     * link quality (0..1). A ride with one sample, or none, still gives something drawable.
     */
    fun graph(summary: RideSummary): List<Pair<Float, Float>> {
        val span = summary.durationMs.coerceAtLeast(1).toFloat()
        val pts = summary.samples.map { (it.atMs / span).coerceIn(0f, 1f) to it.quality }
        return when {
            pts.isEmpty() -> listOf(0f to 1f, 1f to 1f)
            pts.size == 1 -> listOf(0f to pts[0].second, 1f to pts[0].second)
            else -> pts
        }
    }
}
