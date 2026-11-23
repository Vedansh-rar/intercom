package com.intercom.app.security

import com.intercom.app.group.FrameBytes
import com.intercom.app.protocol.AuthChallenge
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.Protocol
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two [SecureChannel]s wired back to back through real executors, as two phones in a
 * group would be, with a tiny key-derivation cost so the whole file runs in seconds.
 */
class SecureChannelTest {

    private fun code(s: String) = (RideCode.parse(s) as RideCode.Parsed.Valid).code
    private val good = code("amber anchor apple arrow aspen")
    private val bad = code("amber anchor apple arrow atlas")

    private val timer = Executors.newScheduledThreadPool(2)
    private val workers = Executors.newFixedThreadPool(2)
    private val rxThreads = mutableListOf<java.util.concurrent.ExecutorService>()

    @After fun tearDown() {
        timer.shutdownNow()
        workers.shutdownNow()
        rxThreads.forEach { it.shutdownNow() }
    }

    /** One phone's end of a connection. */
    private inner class Side(
        c: RideCode,
        sid: Long,
        resendMs: Long = 40L,
        timeoutMs: Long = 15_000L,
    ) {
        /** Frames are delivered here, one at a time, like the group link's receive thread. */
        private val rx = Executors.newSingleThreadExecutor().also { rxThreads += it }
        val results = LinkedBlockingQueue<SecureChannel.Result>()
        val inbox = LinkedBlockingQueue<Message>()
        val sent = CopyOnWriteArrayList<ByteArray>()

        @Volatile var peer: Side? = null

        /** The next this-many frames we send are lost on the air. */
        val loseNext = AtomicInteger(0)

        val channel = SecureChannel(
            code = c,
            sessionId = sid,
            seenNonces = NonceCache(),
            send = { bytes ->
                sent.add(bytes)
                if (loseNext.getAndDecrement() <= 0) peer?.deliver(bytes)
                true
            },
            worker = workers,
            timer = timer,
            onResult = { results.add(it) },
            iterations = 10,
            resendMs = resendMs,
            timeoutMs = timeoutMs,
        )

        fun deliver(bytes: ByteArray) {
            val m = FrameBytes.parse(bytes) ?: return
            rx.execute { channel.receive(m)?.let { inbox.add(it) } }
        }

        fun result(): SecureChannel.Result? = results.poll(10, TimeUnit.SECONDS)
    }

    private fun pair(
        ca: RideCode = good,
        cb: RideCode = good,
        resendMs: Long = 40L,
    ): Pair<Side, Side> {
        val a = Side(ca, 1111L, resendMs)
        val b = Side(cb, 2222L, resendMs)
        a.peer = b
        b.peer = a
        return a to b
    }

    private fun connected(): Pair<Side, Side> {
        val (a, b) = pair()
        a.channel.start(); b.channel.start()
        assertTrue(a.result() is SecureChannel.Result.Authenticated)
        assertTrue(b.result() is SecureChannel.Result.Authenticated)
        return a to b
    }

    // ---- the handshake --------------------------------------------------------

    @Test fun bothSidesAuthenticateWithTheSameCode() {
        val (a, b) = connected()
        assertEquals(ChannelSecurity.Encrypted, a.channel.state)
        assertEquals(ChannelSecurity.Encrypted, b.channel.state)
    }

    @Test fun aWrongCodeIsRefusedOnBothSides() {
        val (a, b) = pair(good, bad)
        a.channel.start(); b.channel.start()
        assertTrue(a.result() is SecureChannel.Result.WrongCode)
        assertTrue(b.result() is SecureChannel.Result.WrongCode)
        assertFalse(a.channel.state == ChannelSecurity.Encrypted)
        assertNull(a.channel.seal(Protocol.TYPE_AUDIO, 0, byteArrayOf(1)))
    }

    @Test fun theResultIsReportedOnlyOnce() {
        val (a, _) = connected()
        a.channel.close()
        assertNull(a.results.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun lostHandshakeFramesAreResentUntilTheyGetThrough() {
        val (a, b) = pair()
        a.loseNext.set(3)   // our first challenges vanish
        b.loseNext.set(2)
        a.channel.start(); b.channel.start()
        assertTrue(a.result() is SecureChannel.Result.Authenticated)
        assertTrue(b.result() is SecureChannel.Result.Authenticated)
    }

    @Test fun aSilentPeerTimesOut() {
        val a = Side(good, 1111L, resendMs = 20L, timeoutMs = 150L) // no peer wired
        a.channel.start()
        val r = a.result()
        assertTrue(r is SecureChannel.Result.Failed)
        assertEquals("timed out", (r as SecureChannel.Result.Failed).reason)
        assertNotNull(a.sent.firstOrNull()) // it did try
    }

    @Test fun aChallengeWithoutASessionIdIsRefused() {
        val a = Side(good, 1111L)            // no peer wired: we feed it by hand
        a.channel.start()
        val nonce = ByteArray(KeyDerivation.NONCE_BYTES) { (it + 1).toByte() }
        val frame = com.intercom.app.protocol.Codec.encode(
            Protocol.TYPE_AUTH_CHALLENGE, 0, 0, AuthChallenge(nonce, 0L).toBytes(),
        )
        a.deliver(frame)
        val r = a.result()
        assertTrue(r is SecureChannel.Result.Failed)
        assertTrue((r as SecureChannel.Result.Failed).reason.contains("group"))
    }

    // ---- before and after authentication --------------------------------------

    @Test fun nothingCanBeSealedBeforeAuthentication() {
        val a = Side(good, 1111L)
        assertNull(a.channel.seal(Protocol.TYPE_AUDIO, 0, byteArrayOf(1)))
        a.channel.start()                    // started, but the peer never answers
        assertNull(a.channel.seal(Protocol.TYPE_AUDIO, 0, byteArrayOf(1)))
    }

    @Test fun cleartextTrafficIsIgnoredUntilAuthenticated() {
        val a = Side(good, 1111L)
        val audio = Message(Protocol.TYPE_AUDIO, 0, 0, byteArrayOf(1, 2, 3))
        assertNull(a.channel.receive(audio))             // never started
        a.channel.start()
        assertNull(a.channel.receive(audio))             // handshake under way
        assertNull(a.channel.receive(Message(Protocol.TYPE_MESH, 0, 0, ByteArray(20))))
    }

    @Test fun cleartextTrafficIsIgnoredEvenAfterAuthentication() {
        val (a, _) = connected()
        // A frame without the encrypted bit in an encrypted channel is never legitimate.
        assertNull(a.channel.receive(Message(Protocol.TYPE_AUDIO, 0, 0, byteArrayOf(1, 2, 3))))
    }

    // ---- sealed traffic -------------------------------------------------------

    @Test fun aSealedFrameArrivesIntactAndIsNotPlaintextOnTheWire() {
        val (a, b) = connected()
        val secret = "turn left at the petrol pump".toByteArray()
        val bytes = a.channel.seal(Protocol.TYPE_MESH, 0, secret)!!

        val onWire = FrameBytes.parse(bytes)!!
        assertFalse("payload must not be the plaintext", onWire.payload.contentEquals(secret))
        assertFalse(String(onWire.payload, Charsets.ISO_8859_1).contains("petrol"))

        b.deliver(bytes)
        val got = b.inbox.poll(5, TimeUnit.SECONDS)
        assertNotNull(got)
        assertEquals(Protocol.TYPE_MESH, got!!.type)
        assertArrayEquals(secret, got.payload)
    }

    @Test fun framesFlowBothWays() {
        val (a, b) = connected()
        a.deliver(b.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(9))!!)
        b.deliver(a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(7))!!)
        assertArrayEquals(byteArrayOf(9), a.inbox.poll(5, TimeUnit.SECONDS)!!.payload)
        assertArrayEquals(byteArrayOf(7), b.inbox.poll(5, TimeUnit.SECONDS)!!.payload)
    }

    @Test fun manyFramesInARowAllArrive() {
        val (a, b) = connected()
        repeat(200) { i -> b.deliver(a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(i.toByte()))!!) }
        repeat(200) { i ->
            val m = b.inbox.poll(5, TimeUnit.SECONDS)
            assertNotNull("frame $i", m)
            assertEquals(i.toByte(), m!!.payload[0])
        }
    }

    @Test fun aTamperedFrameIsRejectedAndDoesNotBlockTheRealOne() {
        val (a, b) = connected()
        val bytes = a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(1, 2, 3, 4))!!
        val forged = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        // Called directly: nothing else is delivering to b, so one thread at a time holds.
        assertNull(b.channel.receive(FrameBytes.parse(forged)!!))
        val real = b.channel.receive(FrameBytes.parse(bytes)!!)
        assertNotNull(real)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), real!!.payload)
    }

    @Test fun aReplayedFrameIsRejected() {
        val (a, b) = connected()
        val bytes = a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(5))!!
        val m = FrameBytes.parse(bytes)!!
        assertNotNull(b.channel.receive(m))
        assertNull(b.channel.receive(m))
    }

    @Test fun aFrameSealedForAnotherPairCannotBeOpened() {
        val (a, _) = connected()
        val (_, d) = connected()            // a different connection, different keys
        val bytes = a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(5))!!
        assertNull(d.channel.receive(FrameBytes.parse(bytes)!!))
    }

    @Test fun sealedFramesDoNotRepeatAnIdenticalPayloadOnTheWire() {
        val (a, _) = connected()
        val x = a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(1, 1, 1, 1))!!
        val y = a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(1, 1, 1, 1))!!
        assertFalse(x.contentEquals(y))
    }

    @Test fun closingStopsSealingAndOpening() {
        val (a, b) = connected()
        val bytes = b.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(3))!!
        a.channel.close()
        assertNull(a.channel.seal(Protocol.TYPE_MESH, 0, byteArrayOf(1)))
        assertNull(a.channel.receive(FrameBytes.parse(bytes)!!))
        assertEquals(ChannelSecurity.Pending, a.channel.state)
    }
}
