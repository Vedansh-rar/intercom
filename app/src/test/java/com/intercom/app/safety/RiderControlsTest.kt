package com.intercom.app.safety

import com.intercom.app.audio.AlertKind
import com.intercom.app.audio.AlertPatterns
import com.intercom.app.audio.AlertSequencer
import com.intercom.app.audio.AudioConstants
import com.intercom.app.call.LinkTone
import com.intercom.app.call.LinkToneLogic
import com.intercom.app.floor.FloorState
import com.intercom.app.media.ButtonAction
import com.intercom.app.media.ButtonGesture
import com.intercom.app.media.ButtonInput
import com.intercom.app.media.ButtonMapping
import com.intercom.app.media.DuckPolicy
import com.intercom.app.transport.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckPolicyTest {
    @Test fun `the buddy speaking ducks the music`() {
        assertTrue(DuckPolicy.shouldDuck(FloorState.Muted, duckWhenITalk = false, deafened = false))
    }

    @Test fun `my own voice ducks it only if I asked`() {
        assertFalse(DuckPolicy.shouldDuck(FloorState.Talking, false, false))
        assertTrue(DuckPolicy.shouldDuck(FloorState.Talking, true, false))
    }

    @Test fun `nobody speaking gives the music back`() {
        assertFalse(DuckPolicy.shouldDuck(FloorState.Idle, true, false))
    }

    @Test fun `deafened never ducks`() {
        for (f in FloorState.entries) assertFalse(DuckPolicy.shouldDuck(f, true, deafened = true))
    }
}

class ButtonMappingTest {
    @Test fun `defaults keep double press for push to talk and long press for end call`() {
        val m = ButtonMapping()
        assertEquals(ButtonAction.None, m.forGesture(ButtonGesture.Single))
        assertEquals(ButtonAction.PushToTalk, m.forGesture(ButtonGesture.Double))
        assertEquals(ButtonAction.Mute, m.forGesture(ButtonGesture.Triple))
        assertEquals(ButtonAction.EndCall, m.forGesture(ButtonGesture.LongPress))
    }

    @Test fun `any input can be set to any action`() {
        var m = ButtonMapping()
        for (input in ButtonInput.entries) for (a in ButtonAction.entries) {
            m = m.with(input, a)
            assertEquals(a, m.actionFor(input))
        }
    }

    @Test fun `setting one input leaves the others alone`() {
        val m = ButtonMapping().with(ButtonInput.Single, ButtonAction.Deafen)
        assertEquals(ButtonAction.Deafen, m.single)
        assertEquals(ButtonMapping().double, m.double)
        assertEquals(ButtonMapping().long, m.long)
    }

    @Test fun `next cycles through every action and wraps`() {
        var a = ButtonAction.entries.first()
        val seen = mutableSetOf<ButtonAction>()
        repeat(ButtonAction.entries.size) { seen += a; a = a.next() }
        assertEquals(ButtonAction.entries.toSet(), seen)
        assertEquals(ButtonAction.entries.first(), a)
    }

    @Test fun `deafen is reachable from a headset gesture`() {
        assertTrue(ButtonAction.entries.contains(ButtonAction.Deafen))
        assertTrue(ButtonAction.entries.contains(ButtonAction.Mute))
    }
}

class LinkToneLogicTest {
    private val l = LinkToneLogic()

    @Test fun `first connect makes no sound`() {
        assertNull(l.onState(LinkState.Connecting, false, 0))
        assertNull(l.onState(LinkState.Handshaking, false, 100))
        assertNull(l.onState(LinkState.Connected, false, 200))
    }

    @Test fun `a drop plays lost and the recovery plays restored`() {
        l.onState(LinkState.Connected, false, 0)
        assertEquals(LinkTone.Lost, l.onState(LinkState.Reconnecting, false, 5_000))
        assertNull(l.onState(LinkState.Connecting, false, 6_000))
        assertEquals(LinkTone.Restored, l.onState(LinkState.Connected, false, 9_000))
        assertNull(l.onState(LinkState.Connected, false, 9_100))
    }

    @Test fun `a flapping link does not repeat lost inside the gap`() {
        l.onState(LinkState.Connected, false, 0)
        assertEquals(LinkTone.Lost, l.onState(LinkState.Reconnecting, false, 1_000))
        l.onState(LinkState.Connected, false, 2_000)
        assertNull(l.onState(LinkState.Reconnecting, false, 3_000))
        // No lost tone, so no restored tone for that drop either.
        assertNull(l.onState(LinkState.Connected, false, 4_000))
    }

    @Test fun `a lost tone is allowed again after the gap`() {
        l.onState(LinkState.Connected, false, 0)
        l.onState(LinkState.Reconnecting, false, 1_000)
        l.onState(LinkState.Connected, false, 2_000)
        assertEquals(LinkTone.Lost, l.onState(LinkState.Reconnecting, false, 1_000 + LinkToneLogic.LOST_GAP_MS))
    }

    @Test fun `a deliberate transport switch is silent both ways`() {
        l.onState(LinkState.Connected, false, 0)
        assertNull(l.onState(LinkState.Reconnecting, switching = true, nowMs = 30_000))
        assertNull(l.onState(LinkState.Connected, switching = true, nowMs = 33_000))
    }

    @Test fun `ending the call resets it`() {
        l.onState(LinkState.Connected, false, 0)
        l.onState(LinkState.Closed, false, 1_000)
        assertNull(l.onState(LinkState.Reconnecting, false, 50_000))
    }

    @Test fun `reconnecting before ever connecting is silent`() {
        assertNull(l.onState(LinkState.Reconnecting, false, 0))
        assertNull(l.onState(LinkState.Connected, false, 5_000))
    }
}

class AlertToneTest {
    private val frame = AudioConstants.FRAME_SAMPLES

    private fun render(seq: AlertSequencer, ms: Int): List<ShortArray> =
        List(ms / AudioConstants.FRAME_MS) { ShortArray(frame).also { seq.mixInto(it) } }

    private fun peak(frames: List<ShortArray>) = frames.maxOf { f -> f.maxOf { kotlin.math.abs(it.toInt()) } }

    @Test fun `nothing is added when nothing is playing`() {
        val buf = ShortArray(frame) { 5 }
        AlertSequencer().mixInto(buf)
        assertTrue(buf.all { it.toInt() == 5 })
    }

    @Test fun `each alert plays for its length and then stops by itself`() {
        for (k in AlertKind.entries) {
            val s = AlertSequencer()
            s.play(k)
            assertTrue(s.isPlaying)
            render(s, AlertPatterns.totalMs(k) + 100)
            assertFalse("$k still playing", s.isPlaying)
        }
    }

    @Test fun `the SOS siren is much louder and much longer than the link tones`() {
        val sos = AlertSequencer().also { it.play(AlertKind.Sos) }
        val lost = AlertSequencer().also { it.play(AlertKind.LinkLost) }
        val sosPeak = peak(render(sos, 400))
        val lostPeak = peak(render(lost, 300))
        assertTrue("sos $sosPeak lost $lostPeak", sosPeak > lostPeak * 2)
        assertTrue(AlertPatterns.totalMs(AlertKind.Sos) >= 4_000)
        assertTrue(AlertPatterns.totalMs(AlertKind.LinkLost) < 600)
    }

    @Test fun `the three alerts have different patterns`() {
        val all = AlertKind.entries.map { AlertPatterns.of(it) }
        assertEquals(3, all.toSet().size)
    }

    @Test fun `a link tone does not cut off a siren, but a siren replaces a link tone`() {
        val s = AlertSequencer()
        s.play(AlertKind.Sos)
        s.play(AlertKind.LinkLost)
        assertEquals(AlertKind.Sos, s.current)
        val t = AlertSequencer()
        t.play(AlertKind.LinkLost)
        t.play(AlertKind.Sos)
        assertEquals(AlertKind.Sos, t.current)
    }

    @Test fun `the tone never clips the output`() {
        val s = AlertSequencer().also { it.play(AlertKind.Sos) }
        // Mixed over a loud signal it saturates rather than wrapping round.
        val buf = ShortArray(frame) { 30_000 }
        s.mixInto(buf)
        assertTrue(buf.all { it in -32768..32767 })
        assertTrue(buf.any { it.toInt() == 32767 })
    }

    @Test fun `stop silences it at once`() {
        val s = AlertSequencer().also { it.play(AlertKind.Sos) }
        s.stop()
        assertFalse(s.isPlaying)
        val buf = ShortArray(frame)
        s.mixInto(buf)
        assertTrue(buf.all { it.toInt() == 0 })
    }
}
