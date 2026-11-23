package com.intercom.app.group

import com.intercom.app.audio.AudioConstants
import com.intercom.app.floor.FloorState
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.PeerInfo
import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNodeTest {

    private fun line(vararg labels: String): SimNet {
        val net = SimNet()
        labels.forEachIndexed { i, l -> net.add(l, 1000 + i) }
        labels.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        return net
    }

    private fun SimNet.n(l: String) = nodes.getValue(l)

    /** One rider speaking with payload byte 10 sounds like 1000 at the listener; allow rounding. */
    private fun assertHeard(expected: Int, actual: Short?) {
        assertTrue("expected audio, heard silence", actual != null)
        assertEquals(expected.toDouble(), actual!!.toDouble(), 1.0)
    }

    // ---- reaching riders --------------------------------------------------------

    @Test fun aRiderTwoHopsAwayIsHeardThroughTheOneBetween() {
        val net = line("A", "B", "C")
        assertTrue(net.n("A").speak(10))
        net.pump()
        assertHeard(1000, net.n("B").hear())
        assertHeard(1000, net.n("C").hear())
    }

    @Test fun theRelayHopCountIsShownInThePeerTable() {
        val net = line("A", "B", "C")
        net.n("A").speak(10)
        net.pump()
        val seenFromB = net.n("B").node.snapshot().single { it.id == net.n("A").id }
        val seenFromC = net.n("C").node.snapshot().single { it.id == net.n("A").id }
        assertEquals(1, seenFromB.hops)
        assertEquals(2, seenFromC.hops)
    }

    @Test fun reachStopsAtThreeHops() {
        val net = line("A", "B", "C", "D", "E")
        net.n("A").speak(10)
        net.pump()
        assertHeard(1000, net.n("D").hear())   // three hops
        assertNull(net.n("E").hear())                      // four: out of range of the mesh
        assertEquals(0, net.n("E").received)
    }

    @Test fun theOriginatorNeverHearsItselfBackFromARing() {
        val net = SimNet()
        for ((i, l) in listOf("A", "B", "C", "D").withIndex()) net.add(l, 10 + i)
        net.link("A", "B"); net.link("B", "C"); net.link("C", "D"); net.link("D", "A")
        net.n("A").speak(10)
        net.pump()
        assertEquals(0, net.n("A").mixer.streamCount)
    }

    @Test fun everyRiderInAFullMeshHearsEachFrameExactlyOnce() {
        val net = SimNet()
        val labels = listOf("A", "B", "C", "D")
        labels.forEachIndexed { i, l -> net.add(l, 10 + i) }
        for (i in labels.indices) for (j in i + 1 until labels.size) net.link(labels[i], labels[j])

        val frames = 5
        repeat(frames) { net.n("A").speak(10); net.pump() }
        for (l in listOf("B", "C", "D")) {
            repeat(frames) { net.n(l).hear() }
            assertEquals("$l decoded each frame once", frames, net.n(l).codecs.single().decoded)
        }
        // Per envelope: 3 sends by the author, then each of the 3 others relays once to 2 phones.
        // The one extra envelope is the FLOOR_TAKEN announced with the first frame.
        assertTrue("flood is bounded: ${net.steps}", net.steps <= (frames + 1) * 9)
    }

    @Test fun twoRoutesToOneRiderDeliverOnce() {
        val net = SimNet()
        for ((i, l) in listOf("A", "B", "C", "D").withIndex()) net.add(l, 10 + i)
        net.link("A", "B"); net.link("A", "C"); net.link("B", "D"); net.link("C", "D")
        repeat(3) { net.n("A").speak(10); net.pump() }
        repeat(3) { net.n("D").hear() }
        assertEquals(3, net.n("D").codecs.single().decoded)
        assertTrue(net.n("D").node.dropped >= 3) // the second copy of each frame was refused
    }

    @Test fun aRelayDoesNotSendBackTheWayItCame() {
        val net = line("A", "B", "C")
        net.n("A").speak(10)
        net.pump()
        assertEquals(0, net.n("A").received)
    }

    @Test fun aLoneRiderHasNobodyToSendTo() {
        val net = SimNet()
        val a = net.add("A", 1)
        assertFalse(a.speak(10))
    }

    // ---- the floor across the group ---------------------------------------------

    @Test fun everyoneSeesWhoHoldsTheFloor() {
        val net = line("A", "B", "C")
        net.n("A").speak(10)
        net.pump()
        for (l in listOf("B", "C")) {
            assertEquals(net.n("A").id, net.n(l).node.floor.holderId())
            assertEquals(FloorState.Muted, net.n(l).node.floor.state())
        }
        assertEquals(FloorState.Talking, net.n("A").node.floor.state())
    }

    @Test fun releasingTheFloorFreesItEverywhere() {
        val net = line("A", "B", "C")
        net.n("A").speak(10)
        net.pump()
        net.n("A").node.localStopped()
        net.pump()
        assertEquals(FloorState.Idle, net.n("B").node.floor.state())
        assertEquals(FloorState.Idle, net.n("C").node.floor.state())
    }

    @Test fun aRiderWithoutTheFloorCannotSendAudio() {
        val net = line("A", "B")
        assertFalse(net.n("A").node.sendAudio(0, byteArrayOf(1)))
    }

    @Test fun aSecondSpeakerWaitsItsTurn() {
        val net = line("A", "B", "C")
        net.n("A").speak(10); net.pump()
        net.n("B").node.localSpeaking() // B is queued behind A: waiting, no floor
        assertFalse(net.n("B").node.sendAudio(0, byteArrayOf(20)))
        assertTrue(net.n("B").node.floor.isWaiting())
    }

    @Test fun theHostTakesTheFloorAndAMemberIsToldToYield() {
        val net = SimNet()
        val host = net.add("H", 500, priority = 1)
        val member = net.add("M", 400, priority = 0)
        net.link("H", "M")
        // Learn each other's priority the normal way, by beacon.
        host.node.beacon(); member.node.beacon(); net.pump()

        var yielded = false
        member.node.onYield = { yielded = true }
        member.speak(10); net.pump()
        host.node.localSpeaking(); net.pump()

        assertTrue(yielded)
        assertEquals(host.id, member.node.floor.holderId())
        assertEquals(FloorState.Talking, host.node.floor.state())
    }

    @Test fun aMemberCannotTakeTheFloorFromTheHost() {
        val net = SimNet()
        val host = net.add("H", 500, priority = 1)
        val member = net.add("M", 400, priority = 0)
        net.link("H", "M")
        host.node.beacon(); member.node.beacon(); net.pump()
        host.speak(10); net.pump()
        assertEquals(GroupFloor.LocalResult.Waiting, member.node.localSpeaking())
    }

    @Test fun aWaitingRiderIsToldWhenTheHostFinishes() {
        val net = SimNet()
        val host = net.add("H", 500, priority = 1)
        val member = net.add("M", 400, priority = 0)
        net.link("H", "M")
        host.node.beacon(); member.node.beacon(); net.pump()
        var free = false
        member.node.onChannelFree = { free = true }
        host.speak(10); net.pump()
        member.node.localSpeaking()
        host.node.localStopped(); net.pump()
        assertTrue(free)
    }

    // ---- who is in the group ----------------------------------------------------

    @Test fun beaconsTeachEveryoneNamesAndPriorities() {
        val net = line("A", "B", "C")
        for (l in listOf("A", "B", "C")) net.n(l).node.beacon()
        net.pump()
        val seen = net.n("C").node.snapshot().associateBy { it.id }
        assertEquals("A", seen.getValue(net.n("A").id).name)
        assertEquals("B", seen.getValue(net.n("B").id).name)
        assertEquals(2, seen.size)
    }

    @Test fun aNewNeighbourIsToldWhoWeAreStraightAway() {
        val net = line("A", "B")
        net.n("A").node.onEndpointReady()
        net.pump()
        assertEquals("A", net.n("B").node.peers.nameOf(net.n("A").id))
    }

    @Test fun aLostConnectionRemovesEveryoneBehindIt() {
        val net = line("A", "B", "C")
        for (l in listOf("A", "B", "C")) net.n(l).node.beacon()
        net.pump()
        assertEquals(2, net.n("A").node.peers.size)
        net.cut("A", "B")
        assertEquals(0, net.n("A").node.peers.size)
        assertEquals(1, net.n("B").node.peers.size) // B lost A, and still has C
        // C is not next to the cut, so it finds out by expiry, not at once.
        assertEquals(2, net.n("C").node.peers.size)
    }

    @Test fun theFloorIsFreedWhenItsHolderLeaves() {
        val net = line("A", "B")
        net.n("A").speak(10); net.pump()
        assertEquals(FloorState.Muted, net.n("B").node.floor.state())
        net.cut("A", "B")
        assertEquals(FloorState.Idle, net.n("B").node.floor.state())
    }

    @Test fun aRiderWhoGoesSilentExpiresAndTheirFloorEnds() {
        val net = line("A", "B")
        net.n("A").speak(10); net.pump()
        net.now += PeerTable.EXPIRY_MS + 1
        net.n("B").node.tick(pushToTalk = false)
        assertEquals(0, net.n("B").node.peers.size)
        assertEquals(FloorState.Idle, net.n("B").node.floor.state())
    }

    @Test fun aRiderWhoRejoinsIsHeardAgain() {
        val net = line("A", "B")
        net.n("A").speak(10); net.pump()
        net.cut("A", "B")
        net.link("A", "B")
        net.n("A").node.onEndpointReady(); net.pump()
        assertTrue(net.n("B").node.peers.contains(net.n("A").id))
        net.n("A").speak(10); net.pump()
        assertHeard(1000, net.n("B").hear())
    }

    // ---- mute -------------------------------------------------------------------

    @Test fun aMutedRiderIsSilentHereButStillRelayedOnward() {
        val net = line("A", "B", "C")
        net.n("B").node.peers.heard(net.n("A").id, 1, "A")
        net.n("B").node.setPeerMuted(net.n("A").id, true)
        net.n("A").speak(10); net.pump()
        assertNull(net.n("B").hear())                 // B muted A
        assertHeard(1000, net.n("C").hear()) // but still passed A's voice to C
    }

    @Test fun aMutedRiderDoesNotDuckEveryoneElse() {
        val net = line("A", "B")
        net.n("B").node.peers.heard(net.n("A").id, 1, "A")
        net.n("B").node.setPeerMuted(net.n("A").id, true)
        net.n("A").speak(10); net.pump()
        assertEquals(FloorState.Idle, net.n("B").node.floor.state())
    }

    @Test fun unmutingBringsTheVoiceBack() {
        val net = line("A", "B")
        net.n("B").node.peers.heard(net.n("A").id, 1, "A")
        net.n("B").node.setPeerMuted(net.n("A").id, true)
        net.n("A").speak(10); net.pump()
        net.n("B").node.setPeerMuted(net.n("A").id, false)
        net.n("A").speak(10); net.pump()
        assertHeard(1000, net.n("B").hear())
    }

    // ---- hostile or broken input ------------------------------------------------

    private fun env(type: Byte = Protocol.TYPE_AUDIO, age: Int = 0, ttl: Int = 3, seq: Long = 1) =
        MeshEnvelope(ttl, 4242, seq, age, type, 0, GroupAudio.pack(0, byteArrayOf(10)))

    private fun deliver(n: SimNet.SimNode, e: MeshEnvelope) =
        n.node.onFrame("X", Message(Protocol.TYPE_MESH, 0, 0, e.toBytes()))

    @Test fun audioPastTheJitterBudgetIsDroppedAndCounted() {
        val net = line("A", "B")
        deliver(net.n("A"), env(age = MeshRouter.AUDIO_BUDGET_MS + 50))
        assertEquals(0, net.n("A").mixer.streamCount)
        assertEquals(1L, net.n("A").node.droppedStale)
    }

    @Test fun aMalformedEnvelopeIsCountedAndIgnored() {
        val net = line("A", "B")
        net.n("A").node.onFrame("X", Message(Protocol.TYPE_MESH, 0, 0, byteArrayOf(1, 2, 3)))
        assertEquals(1L, net.n("A").node.dropped)
        assertEquals(0, net.n("A").node.peers.size)
    }

    @Test fun twoRiderMessagesInAGroupAreIgnored() {
        val net = line("A", "B")
        net.n("A").node.onFrame("X", Message(Protocol.TYPE_AUDIO, 0, 0, byteArrayOf(1, 2, 3)))
        net.n("A").node.onFrame("X", Message(Protocol.TYPE_PING, 0, 0, ByteArray(16)))
        assertEquals(0, net.n("A").node.peers.size)
        assertEquals(0, net.n("A").mixer.streamCount)
    }

    @Test fun anUnknownInnerTypeIsRelayedButNotActedOn() {
        val net = line("A", "B", "C")
        net.n("B").node.onFrame("A", Message(Protocol.TYPE_MESH, 0, 0, env(type = 0x7E).toBytes()))
        net.pump()
        assertEquals(1, net.n("C").received) // passed on for riders that understand it
        assertEquals(0, net.n("C").mixer.streamCount)
    }

    @Test fun aReplayedEnvelopeIsDeliveredOnce() {
        val net = line("A", "B")
        val e = env()
        deliver(net.n("A"), e); deliver(net.n("A"), e); deliver(net.n("A"), e)
        assertEquals(1, net.n("A").mixer.activeStreams())
        net.n("A").hear()
        assertEquals(1, net.n("A").codecs.single().decoded)
    }

    @Test fun peerInfoWithAnOverlongNameIsBounded() {
        val net = line("A", "B")
        val info = PeerInfo(PeerInfo.Status.Ok, "x".repeat(60), 1).toBytes()
        val e = MeshEnvelope(3, 4242, 1, 0, Protocol.TYPE_PEER_INFO, 0, info)
        deliver(net.n("A"), e)
        assertTrue(net.n("A").node.peers.nameOf(4242).length <= PeerInfo.NAME_BYTES)
    }

    @Test fun theMixerNeverHasMoreThanItsCapOfStreamsEvenFromManyRiders() {
        val net = line("A", "B")
        for (s in 1..8) deliver(net.n("A"), MeshEnvelope(3, 9000 + s, 1, 0, Protocol.TYPE_AUDIO, 0, GroupAudio.pack(0, byteArrayOf(1))))
        assertEquals(GroupMixer.MAX_ACTIVE_STREAMS, net.n("A").mixer.activeStreams())
        assertEquals(5L, net.n("A").mixer.droppedOverCap)
        val out = ShortArray(AudioConstants.FRAME_SAMPLES)
        net.n("A").mixer.take(out)
        assertEquals(3, net.n("A").codecs.size)
    }
}
