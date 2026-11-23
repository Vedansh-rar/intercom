package com.intercom.app.summary

/**
 * Collects what the summary screen shows. The call controller feeds it a reading every few
 * seconds; nothing here touches the audio path, and nothing leaves the phone or the process.
 */
class RideRecorder(private val sampleEveryMs: Long = SAMPLE_EVERY_MS) {

    private var startMs = -1L
    private var lastSampleMs = 0L
    private var hasSample = false
    private val samples = ArrayList<LinkSample>()
    private val switches = ArrayList<TransportSwitch>()
    private var lastTransport: String? = null
    private var wasConnected = true
    private var downSince = -1L
    private var downTotal = 0L
    private var losses = 0
    private var riders = 2
    private var group = false

    val started: Boolean get() = startMs >= 0

    fun start(nowMs: Long, group: Boolean = false) {
        reset()
        startMs = nowMs
        this.group = group
    }

    /** Safe to call as often as the caller likes: readings closer together than the interval are skipped. */
    fun onTick(
        nowMs: Long,
        connected: Boolean,
        rttMs: Int,
        lossPercent: Double,
        transport: String?,
        riders: Int = this.riders,
    ) {
        if (startMs < 0) return
        this.riders = maxOf(this.riders, riders)

        if (!connected && wasConnected) {
            losses++
            downSince = nowMs
        } else if (connected && !wasConnected && downSince >= 0) {
            downTotal += nowMs - downSince
            downSince = -1
        }
        wasConnected = connected

        if (transport != null && transport != lastTransport) {
            if (lastTransport != null) switches += TransportSwitch(nowMs - startMs, lastTransport, transport)
            lastTransport = transport
        }

        if (hasSample && nowMs - lastSampleMs < sampleEveryMs) return
        hasSample = true
        lastSampleMs = nowMs
        samples += LinkSample(nowMs - startMs, connected, rttMs, lossPercent, transport ?: lastTransport)
    }

    fun finish(nowMs: Long): RideSummary? {
        if (startMs < 0) return null
        val duration = (nowMs - startMs).coerceAtLeast(0)
        val down = downTotal + if (downSince >= 0) nowMs - downSince else 0L
        val rtts = samples.filter { it.connected && it.rttMs >= 0 }.map { it.rttMs }
        val out = RideSummary(
            durationMs = duration,
            samples = downsample(samples, MAX_POINTS),
            switches = switches.toList(),
            linkLosses = losses,
            downMs = down.coerceAtMost(duration),
            avgRttMs = if (rtts.isEmpty()) null else rtts.average().toInt(),
            worstRttMs = rtts.maxOrNull(),
            worstLossPercent = samples.maxOfOrNull { if (it.connected) it.lossPercent else 0.0 } ?: 0.0,
            riders = riders,
            group = group,
        )
        reset()
        return out
    }

    fun reset() {
        startMs = -1
        lastSampleMs = 0L
        hasSample = false
        samples.clear()
        switches.clear()
        lastTransport = null
        wasConnected = true
        downSince = -1
        downTotal = 0
        losses = 0
        riders = 2
        group = false
    }

    companion object {
        const val SAMPLE_EVERY_MS = 5_000L
        const val MAX_POINTS = 120

        /** Keeps the worst reading of each bucket, so a short drop does not vanish from a long ride. */
        fun downsample(src: List<LinkSample>, max: Int): List<LinkSample> {
            if (src.size <= max || max <= 0) return src.toList()
            val out = ArrayList<LinkSample>(max)
            val per = src.size.toDouble() / max
            for (i in 0 until max) {
                val from = (i * per).toInt()
                val to = (((i + 1) * per).toInt()).coerceAtMost(src.size).coerceAtLeast(from + 1)
                out += src.subList(from, to).minBy { it.quality }
            }
            return out
        }
    }
}
