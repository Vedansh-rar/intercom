package com.intercom.app.safety

import com.intercom.app.protocol.SosAlert
import com.intercom.app.safety.SosTrigger.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTriggerTest {

    private fun tap(t: SosTrigger, at: Long, lengthMs: Long = 120): Outcome {
        t.press(at)
        t.tick(at + lengthMs / 2)
        return t.release(at + lengthMs)
    }

    @Test fun `one tap only arms it`() {
        val t = SosTrigger()
        assertEquals(Outcome.Armed, tap(t, 1_000))
        assertTrue(t.armed)
    }

    @Test fun `a second tap within the window sends`() {
        val t = SosTrigger()
        tap(t, 1_000)
        assertEquals(Outcome.Fire, tap(t, 3_000))
        assertFalse(t.armed)
    }

    @Test fun `a second tap after the window re-arms instead of sending`() {
        val t = SosTrigger()
        tap(t, 1_000)
        assertEquals(Outcome.Armed, tap(t, 1_000 + SosTrigger.ARM_WINDOW_MS + 500))
    }

    @Test fun `an armed button lapses on its own`() {
        val t = SosTrigger()
        tap(t, 0)
        assertEquals(Outcome.None, t.tick(SosTrigger.ARM_WINDOW_MS - 1))
        assertEquals(Outcome.Disarmed, t.tick(SosTrigger.ARM_WINDOW_MS + 200))
        assertFalse(t.armed)
        // And now one tap arms again rather than sending.
        assertEquals(Outcome.Armed, tap(t, 10_000))
    }

    @Test fun `holding for three seconds sends without a second tap`() {
        val t = SosTrigger()
        t.press(0)
        assertEquals(Outcome.None, t.tick(2_900))
        assertEquals(Outcome.Fire, t.tick(3_000))
        // It fires once, and letting go afterwards does nothing more.
        assertEquals(Outcome.None, t.tick(3_100))
        assertEquals(Outcome.None, t.release(3_500))
    }

    @Test fun `letting go early cancels the hold and does not arm`() {
        val t = SosTrigger()
        t.press(0)
        t.tick(1_500)
        assertEquals(Outcome.None, t.release(2_900))
        assertFalse(t.armed)
    }

    @Test fun `hold progress rises to one and is zero when not held`() {
        val t = SosTrigger()
        assertEquals(0f, t.holdProgress(500), 0f)
        t.press(1_000)
        assertEquals(0.5f, t.holdProgress(2_500), 0.001f)
        assertEquals(1f, t.holdProgress(9_000), 0f)
        t.release(9_000)
        assertEquals(0f, t.holdProgress(9_100), 0f)
    }

    @Test fun `a long accidental press does not count as the second tap`() {
        val t = SosTrigger()
        tap(t, 0)
        t.press(1_000)
        assertEquals(Outcome.None, t.release(1_000 + SosTrigger.TAP_MAX_MS + 100))
        assertTrue("still armed, nothing sent", t.armed)
    }

    @Test fun `cancel clears everything`() {
        val t = SosTrigger()
        tap(t, 0)
        t.cancel()
        assertFalse(t.armed)
        assertEquals(Outcome.Armed, tap(t, 100))
    }
}

class SosSenderTest {

    @Test fun `with the link up it sends at once, then at 2 and 5 seconds, then stops`() {
        val s = SosSender()
        s.start(10_000)
        assertTrue(s.due(10_000, linkUp = true))
        assertFalse(s.due(11_000, true))
        assertTrue(s.due(12_000, true))
        assertFalse(s.due(14_999, true))
        assertTrue(s.due(15_000, true))
        assertTrue(s.finished)
        assertEquals(3, s.sendCount)
        assertFalse(s.due(60_000, true))
    }

    @Test fun `with no link it waits and sends when the link returns`() {
        val s = SosSender()
        s.start(0)
        for (t in 0L..30_000L step 1_000) assertFalse(s.due(t, linkUp = false))
        assertTrue(s.active)
        assertTrue(s.due(40_000, true))
        // The follow-ups are timed from that first send, not from the button press.
        assertFalse(s.due(41_000, true))
        assertTrue(s.due(42_000, true))
    }

    @Test fun `it gives up after two minutes without a link`() {
        val s = SosSender()
        s.start(0)
        assertFalse(s.due(SosSender.GIVE_UP_MS + 1, false))
        assertTrue(s.finished)
        assertTrue(s.gaveUp)
        assertFalse(s.due(SosSender.GIVE_UP_MS + 2, true))
    }

    @Test fun `a link drop between sends pauses the schedule`() {
        val s = SosSender()
        s.start(0)
        assertTrue(s.due(0, true))
        assertFalse(s.due(2_000, false))
        assertTrue(s.due(2_500, true))
    }

    @Test fun `cancel stops it`() {
        val s = SosSender()
        s.start(0)
        s.cancel()
        assertFalse(s.due(0, true))
        assertFalse(s.active)
    }

    @Test fun `a new start resets the schedule`() {
        val s = SosSender()
        s.start(0)
        while (!s.finished) s.due(s.sendCount * 3_000L, true)
        s.start(100_000)
        assertTrue(s.due(100_000, true))
        assertEquals(1, s.sendCount)
    }
}

class SosReceiverTest {

    private fun alert(ts: Long) = SosAlert(123, 456, ts)

    @Test fun `a new alert is raised and its repeats are not`() {
        val r = SosReceiver()
        assertTrue(r.accept(0, alert(1_000), nowMs = 5_000))
        assertFalse(r.accept(0, alert(1_000), nowMs = 7_000))
        assertFalse(r.accept(0, alert(1_000), nowMs = 10_000))
    }

    @Test fun `the same stamp from a different rider is a different alert`() {
        val r = SosReceiver()
        assertTrue(r.accept(1, alert(1_000), 5_000))
        assertTrue(r.accept(2, alert(1_000), 20_000))
    }

    @Test fun `a second distinct alert inside the gap is ignored but its resend later is raised`() {
        val r = SosReceiver()
        assertTrue(r.accept(0, alert(1_000), 5_000))
        assertFalse(r.accept(0, alert(2_000), 6_000))
        assertTrue(r.accept(0, alert(2_000), 6_000 + SosReceiver.MIN_GAP_MS))
    }

    @Test fun `an alert without a position is still raised`() {
        val r = SosReceiver()
        assertTrue(r.accept(0, SosAlert(null, null, 9), 0))
    }

    @Test fun `reset forgets what was seen`() {
        val r = SosReceiver()
        r.accept(0, alert(1), 0)
        r.reset()
        assertTrue(r.accept(0, alert(1), 1))
    }

    @Test fun `memory is bounded`() {
        val r = SosReceiver(minGapMs = 0)
        for (i in 0 until 500L) assertTrue(r.accept(0, alert(i), i * 10))
        // The oldest were forgotten, the recent ones still deduplicate.
        assertFalse(r.accept(0, alert(499), 6_000))
    }
}
