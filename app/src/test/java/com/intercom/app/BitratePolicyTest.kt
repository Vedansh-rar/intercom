package com.intercom.app

import com.intercom.app.audio.BitratePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitratePolicyTest {

    private fun policy() = BitratePolicy() // 24k -> 16k -> 12k, down after 2 bad, up after 15 good

    private fun BitratePolicy.feed(n: Int, loss: Double, rtt: Int = 80) = repeat(n) { onSample(loss, rtt) }

    @Test
    fun `starts at the top of the ladder`() {
        assertEquals(24_000, policy().bitrate)
    }

    @Test
    fun `a single bad sample does not move the rate`() {
        val p = policy()
        p.onSample(10.0, 80)
        assertEquals(24_000, p.bitrate)
    }

    @Test
    fun `sustained loss steps down one rung at a time`() {
        val p = policy()
        p.feed(2, 10.0)
        assertEquals(16_000, p.bitrate)
        p.feed(2, 10.0)
        assertEquals(12_000, p.bitrate)
    }

    @Test
    fun `it stops at the bottom rung`() {
        val p = policy()
        p.feed(50, 20.0)
        assertEquals(12_000, p.bitrate)
    }

    @Test
    fun `high round trip time alone counts as bad`() {
        val p = policy()
        p.feed(2, 0.0, rtt = 600)
        assertEquals(16_000, p.bitrate)
    }

    @Test
    fun `recovery is slow and goes up one rung at a time`() {
        val p = policy()
        p.feed(4, 10.0) // down to 12k
        p.feed(14, 0.0)
        assertEquals("not yet: 14 good samples", 12_000, p.bitrate)
        p.feed(1, 0.0)
        assertEquals(16_000, p.bitrate)
        p.feed(15, 0.0)
        assertEquals(24_000, p.bitrate)
    }

    @Test
    fun `a bad sample interrupts a good run`() {
        val p = policy()
        p.feed(2, 10.0) // 16k
        p.feed(10, 0.0)
        p.onSample(10.0, 80) // one bad sample resets the good run
        p.feed(14, 0.0)
        assertEquals(16_000, p.bitrate)
    }

    @Test
    fun `the band between good and bad holds the rate and the runs`() {
        val p = policy()
        p.feed(2, 10.0) // 16k
        p.feed(10, 0.0) // 10 good
        p.feed(5, 2.5)  // between 1% and 4%: neither good nor bad
        p.feed(5, 0.0)  // 15 good in total, so one rung up
        assertEquals(24_000, p.bitrate)
    }

    @Test
    fun `unknown rtt does not block loss based decisions`() {
        val p = policy()
        p.feed(2, 10.0, rtt = -1)
        assertEquals(16_000, p.bitrate)
        p.feed(15, 0.0, rtt = -1)
        assertEquals(24_000, p.bitrate)
    }

    @Test
    fun `reset returns to the top`() {
        val p = policy()
        p.feed(4, 10.0)
        p.reset()
        assertEquals(24_000, p.bitrate)
    }

    @Test
    fun `the ladder must descend`() {
        val bad = runCatching { BitratePolicy(ladder = intArrayOf(16_000, 24_000)) }
        assertTrue(bad.isFailure)
    }
}
