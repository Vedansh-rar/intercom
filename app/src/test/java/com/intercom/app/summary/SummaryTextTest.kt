package com.intercom.app.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTextTest {

    @Test fun clock_formats_minutes_and_hours() {
        assertEquals("0:00", SummaryText.clock(0))
        assertEquals("0:09", SummaryText.clock(9_400))
        assertEquals("1:05", SummaryText.clock(65_000))
        assertEquals("59:59", SummaryText.clock(3_599_000))
        assertEquals("1:02:03", SummaryText.clock(3_723_000))
        assertEquals("10:00:00", SummaryText.clock(36_000_000))
    }

    @Test fun clock_treats_negative_as_zero() {
        assertEquals("0:00", SummaryText.clock(-5_000))
    }

    private fun summary(vararg s: LinkSample, duration: Long = 60_000) = RideSummary(
        durationMs = duration, samples = s.toList(), switches = emptyList(), linkLosses = 0,
        downMs = 0, avgRttMs = null, worstRttMs = null, worstLossPercent = 0.0, riders = 2, group = false,
    )

    @Test fun graph_always_has_something_to_draw() {
        assertEquals(listOf(0f to 1f, 1f to 1f), SummaryText.graph(summary()))
        val one = SummaryText.graph(summary(LinkSample(10_000, true, 50, 0.0, null)))
        assertEquals(2, one.size)
        assertEquals(0f, one.first().first, 0f)
        assertEquals(1f, one.last().first, 0f)
    }

    @Test fun graph_positions_are_fractions_of_the_ride() {
        val g = SummaryText.graph(
            summary(
                LinkSample(0, true, 50, 0.0, null),
                LinkSample(30_000, false, -1, 0.0, null),
                LinkSample(60_000, true, 50, 0.0, null),
            ),
        )
        assertEquals(listOf(0f, 0.5f, 1f), g.map { it.first })
        assertEquals(0f, g[1].second, 0f)           // a dropped link sits on the floor
        assertTrue(g[0].second > 0.9f)
    }

    @Test fun graph_clamps_samples_past_the_end() {
        val g = SummaryText.graph(summary(LinkSample(0, true, 50, 0.0, null), LinkSample(90_000, true, 50, 0.0, null)))
        assertEquals(1f, g.last().first, 0f)
    }
}

class RideSummaryStoreTest {
    private val sample = RideSummary(
        durationMs = 1, samples = emptyList(), switches = emptyList(), linkLosses = 0, downMs = 0,
        avgRttMs = null, worstRttMs = null, worstLossPercent = 0.0, riders = 2, group = false,
    )

    @Test fun publish_then_clear() {
        RideSummaryStore.clear()
        assertNull(RideSummaryStore.latest.value)
        RideSummaryStore.publish(sample)
        assertEquals(sample, RideSummaryStore.latest.value)
        RideSummaryStore.clear()
        assertNull(RideSummaryStore.latest.value)
    }
}
