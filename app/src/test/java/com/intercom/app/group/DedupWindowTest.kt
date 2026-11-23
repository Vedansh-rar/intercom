package com.intercom.app.group

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupWindowTest {
    @Test fun firstSightingIsTrueAndRepeatIsFalse() {
        val d = DedupWindow()
        assertTrue(d.firstSighting(1, 5))
        assertFalse(d.firstSighting(1, 5))
    }

    @Test fun sendersAreIndependent() {
        val d = DedupWindow()
        assertTrue(d.firstSighting(1, 5))
        assertTrue(d.firstSighting(2, 5))
        assertFalse(d.firstSighting(2, 5))
    }

    @Test fun outOfOrderWithinTheWindowIsAcceptedOnce() {
        val d = DedupWindow()
        assertTrue(d.firstSighting(1, 10))
        assertTrue(d.firstSighting(1, 8))
        assertFalse(d.firstSighting(1, 8))
        assertTrue(d.firstSighting(1, 9))
    }

    @Test fun aStragglerOlderThanTheWindowIsRefused() {
        val d = DedupWindow()
        assertTrue(d.firstSighting(1, 200))
        assertFalse(d.firstSighting(1, 200 - DedupWindow.WINDOW))
        assertTrue(d.firstSighting(1, 200 - DedupWindow.WINDOW + 1))
    }

    @Test fun theTableIsBoundedAndForgetsTheLeastRecentSender() {
        val d = DedupWindow(maxSenders = 4)
        for (s in 1..10) d.firstSighting(s, 1)
        assertEquals(4, d.trackedSenders)
        // Sender 1 was evicted, so its old message looks new again: bounded memory is the
        // trade, and a sender silent that long is not a flood in progress.
        assertTrue(d.firstSighting(1, 1))
    }

    @Test fun forgetClearsOneSender() {
        val d = DedupWindow()
        d.firstSighting(1, 1)
        d.forget(1)
        assertTrue(d.firstSighting(1, 1))
    }

    @Test fun aThousandThreadsRacingForOneMessageYieldExactlyOneWinner() {
        val d = DedupWindow()
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(1000)
        repeat(1000) {
            pool.execute {
                start.await()
                if (d.firstSighting(42, 7)) winners.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, winners.get())
    }

    @Test fun concurrentDistinctMessagesAreAllAcceptedOnce() {
        val d = DedupWindow()
        val accepted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) { t ->
            pool.execute {
                // Each thread is a different sender, counting up: no sharing, no loss.
                for (i in 0L until 500L) if (d.firstSighting(t, i)) accepted.incrementAndGet()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(2000, accepted.get())
    }
}
