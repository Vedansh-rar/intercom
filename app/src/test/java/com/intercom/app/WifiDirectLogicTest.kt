package com.intercom.app

import com.intercom.app.transport.ConnectGate
import com.intercom.app.transport.ConnectRetryPolicy
import com.intercom.app.transport.OwnerAddress
import com.intercom.app.transport.PeerCandidate
import com.intercom.app.transport.PeerPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectLogicTest {

    // ------------------------------------------------------------ PeerPicker

    @Test
    fun `picker prefers a group owner`() {
        val picked = PeerPicker.pick(
            listOf(
                PeerCandidate("aa:aa", false),
                PeerCandidate("bb:bb", true),
                PeerCandidate("cc:cc", true),
            ),
        )
        assertEquals("bb:bb", picked)
    }

    @Test
    fun `picker falls back to the first device`() {
        assertEquals("aa:aa", PeerPicker.pick(listOf(PeerCandidate("aa:aa", false), PeerCandidate("bb:bb", false))))
    }

    @Test
    fun `picker ignores blank addresses and handles empty`() {
        assertNull(PeerPicker.pick(emptyList()))
        assertNull(PeerPicker.pick(listOf(PeerCandidate("", true), PeerCandidate("  ", false))))
        assertEquals("bb:bb", PeerPicker.pick(listOf(PeerCandidate("", true), PeerCandidate("bb:bb", false))))
    }

    // ------------------------------------------------------------ ConnectGate

    @Test
    fun `gate allows the first connect`() {
        assertTrue(ConnectGate().mayConnect("aa:aa", 1_000))
    }

    @Test
    fun `gate blocks a repeat to the same peer inside the interval`() {
        val g = ConnectGate(minIntervalMs = 12_000)
        g.recordAttempt("aa:aa", 1_000)
        assertFalse(g.mayConnect("aa:aa", 5_000))
        assertFalse(g.mayConnect("aa:aa", 12_999))
        assertTrue(g.mayConnect("aa:aa", 13_000))
    }

    @Test
    fun `gate lets a different peer through at once`() {
        val g = ConnectGate()
        g.recordAttempt("aa:aa", 1_000)
        assertTrue(g.mayConnect("bb:bb", 1_100))
    }

    @Test
    fun `gate reset after a failure allows an immediate retry`() {
        val g = ConnectGate()
        g.recordAttempt("aa:aa", 1_000)
        assertTrue(g.hasAttempted)
        g.reset()
        assertFalse(g.hasAttempted)
        assertTrue(g.mayConnect("aa:aa", 1_050))
    }

    // ------------------------------------------------- ConnectRetryPolicy

    @Test
    fun `retry delays grow then plateau`() {
        val p = ConnectRetryPolicy(longArrayOf(100, 200, 400), totalBudgetMs = 100_000)
        assertEquals(100L, p.delayBefore(1, 0))
        assertEquals(200L, p.delayBefore(2, 0))
        assertEquals(400L, p.delayBefore(3, 0))
        assertEquals(400L, p.delayBefore(9, 0))
    }

    @Test
    fun `retry stops when the budget is spent`() {
        val p = ConnectRetryPolicy(longArrayOf(1000), totalBudgetMs = 5_000)
        assertNotNull(p.delayBefore(1, 3_000))
        assertNull(p.delayBefore(1, 4_500))   // the wait would overrun the budget
        assertNull(p.delayBefore(1, 5_000))
        assertNull(p.delayBefore(1, 9_000))
    }

    @Test
    fun `default policy tolerates a host that listens a couple of seconds late`() {
        val p = ConnectRetryPolicy()
        var elapsed = 0L
        var tries = 0
        while (elapsed < 3_000) {
            tries++
            elapsed += p.delayBefore(tries, elapsed) ?: break
        }
        assertTrue("should still be retrying at 3 s, tries=$tries", elapsed >= 3_000)
    }

    @Test
    fun `default policy gives up eventually`() {
        val p = ConnectRetryPolicy()
        var elapsed = 0L
        var tries = 0
        while (tries < 100) {
            tries++
            elapsed += p.delayBefore(tries, elapsed) ?: break
        }
        assertTrue(tries < 100)
    }

    // ------------------------------------------------------- OwnerAddress

    @Test
    fun `a reported IPv4 address is used`() {
        assertEquals("192.168.49.1", OwnerAddress.resolve("192.168.49.1"))
        assertEquals("192.168.49.7", OwnerAddress.resolve("/192.168.49.7"))
        assertEquals("10.1.2.3", OwnerAddress.resolve(" 10.1.2.3 "))
    }

    @Test
    fun `junk falls back to the fixed owner address`() {
        for (bad in listOf(null, "", "  ", "fe80::1", "300.1.1.1", "1.2.3", "a.b.c.d", "1.2.3.4.5", "1..2.3")) {
            assertEquals("input=$bad", OwnerAddress.FALLBACK, OwnerAddress.resolve(bad))
        }
    }
}

class TestListenerPolicyTest {
    @Test
    fun `release builds never open the test listener`() {
        assertNull(com.intercom.app.transport.RfcommLink.testListenerBindAddress(false))
    }

    @Test
    fun `debug builds bind loopback only`() {
        val a = com.intercom.app.transport.RfcommLink.testListenerBindAddress(true)
        assertNotNull(a)
        assertTrue(a!!.isLoopbackAddress)
    }
}
