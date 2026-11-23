package com.intercom.app.group

import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterTest {
    private val me = 111
    private fun env(
        sender: Int = 222, seq: Long = 1, ttl: Int = 3, age: Int = 0,
        type: Byte = Protocol.TYPE_AUDIO,
    ) = MeshEnvelope(ttl, sender, seq, age, type, 1, byteArrayOf(9))

    @Test fun deliversAndRelaysAFreshMessageWithHopsLeft() {
        val d = MeshRouter(me).onReceive(env(ttl = 3))
        assertTrue(d.deliver)
        assertNotNull(d.relay)
        assertEquals(2, d.relay!!.ttl)
    }

    @Test fun deliversButDoesNotRelayWhenTheLastHopIsUsed() {
        val d = MeshRouter(me).onReceive(env(ttl = 1))
        assertTrue(d.deliver)
        assertNull(d.relay)
    }

    @Test fun dropsOurOwnMessageComingBackRoundALoop() {
        val d = MeshRouter(me).onReceive(env(sender = me))
        assertFalse(d.deliver)
        assertNull(d.relay)
        assertEquals("own message", d.dropReason)
    }

    @Test fun dropsADuplicateByAnyRoute() {
        val r = MeshRouter(me)
        assertTrue(r.onReceive(env(seq = 5, ttl = 3)).deliver)
        val again = r.onReceive(env(seq = 5, ttl = 2))
        assertFalse(again.deliver)
        assertNull(again.relay)
        assertEquals("duplicate", again.dropReason)
    }

    @Test fun differentSequencesFromOneSenderAreDistinct() {
        val r = MeshRouter(me)
        assertTrue(r.onReceive(env(seq = 1)).deliver)
        assertTrue(r.onReceive(env(seq = 2)).deliver)
    }

    @Test fun dropsAudioOlderThanTheJitterBudget() {
        val r = MeshRouter(me)
        val d = r.onReceive(env(age = MeshRouter.AUDIO_BUDGET_MS + 1))
        assertFalse(d.deliver)
        assertNull(d.relay)
        assertEquals("audio too old", d.dropReason)
    }

    @Test fun theBudgetIsTheJitterBufferCeiling() {
        // 10 frames of 20 ms.
        assertEquals(200, MeshRouter.AUDIO_BUDGET_MS)
    }

    @Test fun audioExactlyAtTheBudgetIsStillDelivered() {
        val d = MeshRouter(me).onReceive(env(age = MeshRouter.AUDIO_BUDGET_MS, ttl = 1))
        assertTrue(d.deliver)
    }

    @Test fun nonAudioIsNotAgeLimited() {
        val d = MeshRouter(me).onReceive(env(type = Protocol.TYPE_FLOOR_TAKEN, age = 60_000))
        assertTrue(d.deliver)
        assertNotNull(d.relay)
    }

    @Test fun relayAddsResidenceTimePlusTheHopAllowance() {
        val d = MeshRouter(me).onReceive(env(age = 20), residenceMs = 7)
        assertEquals(20 + 7 + MeshRouter.HOP_ALLOWANCE_MS, d.relay!!.ageMs)
    }

    @Test fun audioThatWouldArriveLateIsDeliveredHereButNotForwarded() {
        // Still playable on this phone, but the next hop would add more than the budget has left.
        val d = MeshRouter(me).onReceive(env(age = MeshRouter.AUDIO_BUDGET_MS - 5), residenceMs = 0)
        assertTrue(d.deliver)
        assertNull(d.relay)
    }

    @Test fun negativeResidenceIsTreatedAsZero() {
        val d = MeshRouter(me).onReceive(env(age = 0), residenceMs = -100)
        assertEquals(MeshRouter.HOP_ALLOWANCE_MS, d.relay!!.ageMs)
    }

    @Test fun originateStampsOurIdFullTtlAndCountingSequence() {
        val r = MeshRouter(me)
        val a = r.originate(Protocol.TYPE_AUDIO, 1, byteArrayOf(1))
        val b = r.originate(Protocol.TYPE_AUDIO, 1, byteArrayOf(2))
        assertEquals(me, a.senderId)
        assertEquals(MeshEnvelope.MAX_TTL, a.ttl)
        assertEquals(0, a.ageMs)
        assertEquals(a.seq + 1, b.seq)
    }

    @Test fun ourOwnOriginatedMessageIsRefusedIfItComesBack() {
        val r = MeshRouter(me)
        val sent = r.originate(Protocol.TYPE_AUDIO, 1, byteArrayOf(1))
        assertFalse(r.onReceive(sent.relayed(0)).deliver)
    }

    @Test fun forgetLetsAReturningSenderStartAgain() {
        val r = MeshRouter(me)
        assertTrue(r.onReceive(env(seq = 1)).deliver)
        r.forget(222)
        assertTrue(r.onReceive(env(seq = 1)).deliver)
    }
}
