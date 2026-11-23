package com.intercom.app.group

import com.intercom.app.protocol.Message
import com.intercom.app.protocol.Protocol
import com.intercom.app.relay.RelayConnector
import com.intercom.app.relay.RelayFailure
import com.intercom.app.relay.RelayProtocol
import com.intercom.app.relay.RelaySocket
import com.intercom.app.relay.RelaySocketEvents
import com.intercom.app.security.RideCode
import com.intercom.app.transport.LinkState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two or three [InternetLink]s talking through an in-memory stand-in for `server/relay.js`:
 * the real handshake, the real encryption, the real framing - only the WebSocket is faked.
 */
class InternetLinkTest {

    /** Mirrors the server's behaviour: welcome, join/leave notices, forwarding to a named member. */
    private class FakeRelay {
        private val io = Executors.newSingleThreadExecutor()
        private val ids = AtomicInteger(100)
        val rooms = HashMap<String, LinkedHashMap<Int, Member>>()
        val connects = AtomicInteger()
        val roomsAsked = CopyOnWriteArrayList<String>()
        @Volatile var refuseWith: RelayFailure? = null

        class Member(val id: Int, val events: RelaySocketEvents) { @Volatile var open = true }

        fun connector(room: String): RelayConnector = RelayConnector { events ->
            connects.incrementAndGet()
            roomsAsked += room
            val id = ids.incrementAndGet()
            val member = Member(id, events)
            io.execute {
                val refuse = refuseWith
                if (refuse != null) { member.open = false; events.onClosed(refuse); return@execute }
                val members = synchronized(rooms) { rooms.getOrPut(room) { LinkedHashMap() } }
                synchronized(members) {
                    events.onMessage(byteArrayOf(RelayProtocol.WELCOME) + int(id) + byteArrayOf(0x08, 0x00))
                    for (o in members.values) {
                        events.onMessage(byteArrayOf(RelayProtocol.JOIN) + int(o.id))
                        o.events.onMessage(byteArrayOf(RelayProtocol.JOIN) + int(id))
                    }
                    members[id] = member
                }
            }
            object : RelaySocket {
                override fun send(bytes: ByteArray): Boolean {
                    if (!member.open || bytes.size < 5 || bytes[0] != RelayProtocol.SEND) return false
                    val dst = java.nio.ByteBuffer.wrap(bytes, 1, 4).int
                    io.execute {
                        val members = synchronized(rooms) { rooms[room] } ?: return@execute
                        val target = synchronized(members) { members[dst] } ?: return@execute
                        target.events.onMessage(byteArrayOf(RelayProtocol.DATA) + int(id) + bytes.copyOfRange(5, bytes.size))
                    }
                    return true
                }
                override fun close() { leave(room, member) }
            }
        }

        private fun leave(room: String, m: Member) {
            if (!m.open) return
            m.open = false
            io.execute {
                val members = synchronized(rooms) { rooms[room] } ?: return@execute
                synchronized(members) {
                    members.remove(m.id)
                    for (o in members.values) o.events.onMessage(byteArrayOf(RelayProtocol.LEAVE) + int(m.id))
                }
            }
        }

        /** The network went away for everyone connected: each side sees its socket close. */
        fun dropEveryone(failure: RelayFailure = RelayFailure.Lost) {
            io.execute {
                val all = synchronized(rooms) { rooms.values.toList() }
                for (members in all) synchronized(members) {
                    val list = members.values.toList()
                    members.clear()
                    for (m in list) { m.open = false; m.events.onClosed(failure) }
                }
            }
        }

        fun injectData(room: String, toId: Int, fromId: Int, payload: ByteArray) {
            io.execute {
                val members = synchronized(rooms) { rooms[room] } ?: return@execute
                val t = synchronized(members) { members[toId] } ?: return@execute
                t.events.onMessage(byteArrayOf(RelayProtocol.DATA) + int(fromId) + payload)
            }
        }

        fun shutdown() = io.shutdownNow()

        private fun int(v: Int) = java.nio.ByteBuffer.allocate(4).putInt(v).array()
    }

    private class Recorder : GroupLink.Listener {
        val frames = CopyOnWriteArrayList<Pair<String, Message>>()
        val ready = CopyOnWriteArrayList<String>()
        val gone = CopyOnWriteArrayList<String>()
        val wrongCode = AtomicInteger()
        override fun onFrame(endpoint: String, message: Message, receivedAt: Long) { frames += endpoint to message }
        override fun onEndpointReady(endpoint: String) { ready += endpoint }
        override fun onEndpointGone(endpoint: String) { gone += endpoint }
        override fun onWrongCode() { wrongCode.incrementAndGet() }
    }

    private val relay = FakeRelay()
    private val links = mutableListOf<InternetLink>()
    private val code = RideCode.parseOrNull("amber anchor apple arrow aspen atlas")!!
    private val otherCode = RideCode.parseOrNull("amber anchor apple arrow aspen autumn")!!

    @After fun tearDown() {
        links.forEach { it.close() }
        relay.shutdown()
    }

    private fun link(
        c: RideCode = code,
        session: Long = (links.size + 1).toLong(),
        backoffMs: Long = 20,
        roomIdOf: (RideCode) -> String = { "room-${it.canonical.hashCode()}" },
    ): Pair<InternetLink, Recorder> {
        val l = InternetLink(
            sessionId = session,
            code = c,
            connectorFor = { room -> relay.connector(room) },
            iterations = 1,
            roomIdOf = roomIdOf,
            backoff = { _, _ -> backoffMs },
        )
        val r = Recorder()
        l.listener = r
        links += l
        return l to r
    }

    private fun await(what: String, ms: Long = 8000, cond: () -> Boolean) {
        val t = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - t > ms) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    // ---- the happy path ----------------------------------------------------------

    @Test fun twoRidersWithTheSameCodeConnectAndExchangeAnEncryptedFrame() {
        val (a, ra) = link(); val (b, rb) = link()
        a.start(); b.start()
        await("both connected") { a.state.value == LinkState.Connected && b.state.value == LinkState.Connected }
        assertEquals("Connected to 1 rider", a.status.value)
        await("ready callbacks") { ra.ready.size == 1 && rb.ready.size == 1 }

        val to = a.readyEndpoints().single()
        assertTrue(a.sendTo(to, Protocol.TYPE_MESH, 0, byteArrayOf(1, 2, 3, 4)))
        await("frame arrives") { rb.frames.isNotEmpty() }
        val (from, msg) = rb.frames.first()
        assertEquals(Protocol.TYPE_MESH, msg.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), msg.payload)
        assertEquals(b.readyEndpoints().single(), from)
    }

    @Test fun nothingOnTheRelayIsPlaintext() {
        val seen = CopyOnWriteArrayList<ByteArray>()
        val (a, _) = link()
        val wrapped = InternetLink(
            sessionId = 99, code = code, iterations = 1, roomIdOf = { "room-${code.canonical.hashCode()}" },
            backoff = { _, _ -> 20 },
            connectorFor = { room ->
                val inner = relay.connector(room)
                RelayConnector { ev ->
                    val s = inner.connect(ev)
                    object : RelaySocket {
                        override fun send(bytes: ByteArray): Boolean { seen += bytes; return s.send(bytes) }
                        override fun close() = s.close()
                    }
                }
            },
        ).also { links += it; it.listener = Recorder() }
        a.start(); wrapped.start()
        await("connected") { wrapped.state.value == LinkState.Connected }
        val marker = "SECRET-VOICE-PAYLOAD".toByteArray()
        assertTrue(wrapped.sendTo(wrapped.readyEndpoints().single(), Protocol.TYPE_MESH, 0, marker))
        await("sent") { seen.any { it.size > marker.size } }
        for (s in seen) assertFalse("payload leaked to the relay", indexOf(s, marker) >= 0)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test fun threeRidersAreAFullMesh() {
        val (a, _) = link(); val (b, _) = link(); val (c, rc) = link()
        a.start(); b.start(); c.start()
        await("all connected") { listOf(a, b, c).all { it.readyEndpoints().size == 2 } }
        assertEquals("Connected to 2 riders", a.status.value)
        // The PeerLink send() path goes to every authenticated rider.
        assertTrue(c.send(com.intercom.app.protocol.Codec.encode(Protocol.TYPE_MESH, 0, 0, byteArrayOf(9))))
        for (e in a.readyEndpoints()) assertTrue(a.sendTo(e, Protocol.TYPE_MESH, 0, byteArrayOf(7)))
        await("c hears a") { rc.frames.isNotEmpty() }
    }

    @Test fun theRoomComesFromTheCodeSoRidersNeedNothingElse() {
        val (a, _) = link(); val (b, _) = link(c = otherCode)
        a.start(); b.start()
        await("both asked") { relay.roomsAsked.size == 2 }
        assertEquals(2, relay.roomsAsked.toSet().size)
    }

    // ---- who gets in -------------------------------------------------------------

    @Test fun aRiderWithTheWrongCodeNeverGetsPastTheHandshake() {
        val same = { _: RideCode -> "shared-room" }
        val (a, ra) = link(roomIdOf = same); val (b, rb) = link(c = otherCode, roomIdOf = same)
        a.start(); b.start()
        await("a wrong-code verdict") { ra.wrongCode.get() > 0 || rb.wrongCode.get() > 0 }
        assertTrue(a.readyEndpoints().isEmpty())
        assertTrue(b.readyEndpoints().isEmpty())
        assertTrue(ra.ready.isEmpty() && rb.ready.isEmpty())
        assertTrue(ra.frames.isEmpty() && rb.frames.isEmpty())
    }

    @Test fun dataFromSomeoneWhoNeverJoinedIsIgnored() {
        val (a, ra) = link()
        a.start()
        await("listening") { a.state.value == LinkState.Listening }
        relay.injectData("room-${code.canonical.hashCode()}", 101, 4242, byteArrayOf(0xAA.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9))
        Thread.sleep(150)
        assertTrue(ra.frames.isEmpty())
        assertEquals(LinkState.Listening, a.state.value)
    }

    @Test fun beforeTheHandshakeNothingCanBeSent() {
        val (a, _) = link()
        assertFalse(a.sendTo("00000001", Protocol.TYPE_MESH, 0, byteArrayOf(1)))
        assertTrue(a.readyEndpoints().isEmpty())
    }

    // ---- patchy mobile data --------------------------------------------------------

    @Test fun aDroppedConnectionReconnectsAndRehandshakes() {
        val (a, ra) = link(); val (b, rb) = link()
        a.start(); b.start()
        await("connected") { a.state.value == LinkState.Connected && b.state.value == LinkState.Connected }

        relay.dropEveryone()
        await("reconnecting") { a.state.value == LinkState.Reconnecting || relay.connects.get() > 2 }
        await("goes gone") { ra.gone.isNotEmpty() && rb.gone.isNotEmpty() }
        await("connected again") { a.state.value == LinkState.Connected && b.state.value == LinkState.Connected }
        await("second ready") { ra.ready.size == 2 && rb.ready.size == 2 }

        // New keys, and traffic flows again.
        assertTrue(a.sendTo(a.readyEndpoints().single(), Protocol.TYPE_MESH, 0, byteArrayOf(5)))
        await("frame after reconnect") { rb.frames.isNotEmpty() }
    }

    @Test fun aRefusedAddressStopsTryingInsteadOfHammeringTheServer() {
        relay.refuseWith = RelayFailure.Refused
        val (a, _) = link()
        a.start()
        await("failed") { a.state.value == LinkState.Failed }
        assertEquals("The relay address was refused", a.status.value)
        Thread.sleep(200)
        assertEquals(1, relay.connects.get())
    }

    @Test fun aFullRoomKeepsTryingSlowlyAndSaysSo() {
        relay.refuseWith = RelayFailure.Full
        val (a, _) = link()
        a.start()
        await("reconnecting") { a.state.value == LinkState.Reconnecting }
        assertEquals("The room is full", a.status.value)
        await("tried again") { relay.connects.get() >= 2 }
        relay.refuseWith = null
        await("joins once there is space") { a.state.value == LinkState.Listening }
    }

    @Test fun aLostConnectionSaysSo() {
        val (a, _) = link(backoffMs = 5_000)
        a.start()
        await("listening") { a.state.value == LinkState.Listening }
        relay.dropEveryone()
        await("reconnecting") { a.state.value == LinkState.Reconnecting }
        assertEquals("Lost the connection - reconnecting", a.status.value)
    }

    // ---- ending ----------------------------------------------------------------------

    @Test fun closingTellsTheOthersAndDoesNotReconnect() {
        val (a, _) = link(); val (b, rb) = link()
        a.start(); b.start()
        await("connected") { a.state.value == LinkState.Connected && b.state.value == LinkState.Connected }
        a.close()
        assertEquals(LinkState.Closed, a.state.value)
        await("b sees a leave") { rb.gone.isNotEmpty() }
        assertEquals(LinkState.Listening, b.state.value)
        val connects = relay.connects.get()
        Thread.sleep(200)
        assertEquals(connects, relay.connects.get())
    }

    @Test fun startingTwiceOpensOneConnection() {
        val (a, _) = link()
        a.start(); a.start()
        await("listening") { a.state.value == LinkState.Listening }
        assertEquals(1, relay.connects.get())
    }

    @Test fun theLinkReportsTheInternetTransport() {
        val (a, _) = link()
        assertEquals(com.intercom.app.transport.Transport.Internet, a.transport)
    }
}
