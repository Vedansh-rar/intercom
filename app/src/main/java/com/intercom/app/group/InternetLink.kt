package com.intercom.app.group

import android.util.Log
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.Message
import com.intercom.app.relay.Backoff
import com.intercom.app.relay.InternetRoom
import com.intercom.app.relay.RelayConnector
import com.intercom.app.relay.RelayFailure
import com.intercom.app.relay.RelayProtocol
import com.intercom.app.relay.RelaySocket
import com.intercom.app.relay.RelaySocketEvents
import com.intercom.app.security.AuthThrottle
import com.intercom.app.security.KeyDerivation
import com.intercom.app.security.NonceCache
import com.intercom.app.security.RideCode
import com.intercom.app.security.SecureChannel
import com.intercom.app.transport.LinkState
import com.intercom.app.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Group-ride transport over the internet: one WebSocket to a relay, in a room named by the
 * ride code ([InternetRoom]), and one [SecureChannel] per other rider in the room.
 *
 * The relay is a dumb pipe. It says who is in the room and forwards packets between named
 * riders, and it holds no key: each pair of riders runs the same handshake as every other
 * ride (proof of the ride code, then AES-GCM with a per-connection key), carried inside the
 * relay's packets. So the relay cannot hear, alter, or replay a rider's voice, and a rider
 * in the room who does not know the code never gets past the handshake.
 *
 * It is a full mesh: every rider is a neighbour of every other, so there is nothing to relay
 * ([GroupNode.relayEnabled] is switched off for it) and the first hop is the only hop.
 *
 * ## When the connection drops
 * On a bike the data connection goes away in tunnels and at cell handovers. A drop closes
 * every neighbour (the others see this rider leave), and the link reconnects with a growing
 * delay ([Backoff]); a new connection gets a new id, so every pair handshakes afresh with
 * new keys. Audio simply gaps until it is back.
 *
 * ## Threads
 * Everything the relay says is handled on one executor (which also keeps each channel's
 * replay window single-threaded); key derivation runs on a worker pool; reconnects and
 * handshake resends on a timer. The neighbour table is a [ConcurrentHashMap] because the
 * capture thread reads it.
 *
 * No Android classes are needed, so this runs in JVM unit tests against a fake relay.
 */
class InternetLink(
    private val sessionId: Long,
    private val code: RideCode,
    private val connectorFor: (roomId: String) -> RelayConnector,
    private val throttle: AuthThrottle = AuthThrottle(),
    private val seenNonces: NonceCache = NonceCache(),
    private val iterations: Int = KeyDerivation.DEFAULT_ITERATIONS,
    private val roomIdOf: (RideCode) -> String = { InternetRoom.roomId(it, iterations) },
    private val backoff: (attempt: Int, failure: RelayFailure) -> Long = { a, f -> Backoff.delayMs(a, f, 0.8 + Math.random() * 0.4) },
) : GroupTransport {

    @Volatile override var listener: GroupLink.Listener? = null

    private class Endpoint(val id: Int, val key: String) {
        @Volatile var channel: SecureChannel? = null
        @Volatile var ready = false
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()

    private val rx = Executors.newSingleThreadExecutor(ThreadFactory { r -> Thread(r, "inet-rx") })
    private val worker = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "inet-auth") })
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "inet-timer") })

    private val stateFlow = MutableStateFlow(LinkState.Idle)
    private val statusFlow = MutableStateFlow("Not started")
    private val rttFlow = MutableStateFlow(-1)
    private val helloFlow = MutableStateFlow<Hello?>(null)

    override val transport: Transport = Transport.Internet
    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val rttMillis: StateFlow<Int> = rttFlow.asStateFlow()
    override val peerHello: StateFlow<Hello?> = helloFlow.asStateFlow()
    override val status: StateFlow<String> = statusFlow.asStateFlow()
    override val clockOffsetMicros: Long = 0L

    @Volatile private var started = false
    @Volatile private var closed = false
    private var onMessage: (Message) -> Unit = {}

    @Volatile private var room: String? = null
    @Volatile private var socket: RelaySocket? = null
    private val generation = AtomicInteger()
    @Volatile private var selfRelayId = 0
    @Volatile private var maxPayload = DEFAULT_MAX_PAYLOAD
    @Volatile private var relayUp = false
    @Volatile private var gaveUp = false
    private var attempt = 0

    // ------------------------------------------------------------ lifecycle

    override fun start() {
        if (started) return
        started = true
        stateFlow.value = LinkState.Connecting
        statusFlow.value = "Connecting to the relay"
        // The room id is a slow hash: keep it off the caller's thread.
        worker.execute {
            try {
                room = roomIdOf(code)
                connectNow()
            } catch (t: Throwable) {
                Log.w(TAG, "could not start: ${t.message}")
                giveUp("The relay address was refused")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        runCatching { socket?.close() }
        socket = null
        for (e in endpoints.values) e.channel?.close()
        endpoints.clear()
        timer.shutdownNow()
        worker.shutdownNow()
        rx.shutdown()
        stateFlow.value = LinkState.Closed
    }

    private fun connectNow() {
        if (closed || gaveUp) return
        val gen = generation.incrementAndGet()
        val r = room ?: return
        relayUp = false
        try {
            socket = connectorFor(r).connect(Events(gen))
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed: ${t.message}")
            if (!rx.isShutdown) rx.execute { if (gen == generation.get()) onSocketClosed(RelayFailure.Lost) }
        }
    }

    private inner class Events(private val gen: Int) : RelaySocketEvents {
        override fun onOpen() = Unit

        override fun onMessage(bytes: ByteArray) {
            val at = System.currentTimeMillis()
            if (!rx.isShutdown) rx.execute { if (gen == generation.get()) handleRelay(bytes, at) }
        }

        override fun onClosed(failure: RelayFailure) {
            if (!rx.isShutdown) rx.execute { if (gen == generation.get()) onSocketClosed(failure) }
        }
    }

    // ------------------------------------------------------------ what the relay says

    private fun handleRelay(bytes: ByteArray, receivedAt: Long) {
        when (val m = RelayProtocol.parse(bytes)) {
            is RelayProtocol.Incoming.Welcome -> {
                selfRelayId = m.id
                maxPayload = if (m.maxPayload in 256..MAX_PAYLOAD_CAP) m.maxPayload else DEFAULT_MAX_PAYLOAD
                relayUp = true
                attempt = 0
                refreshState()
            }
            is RelayProtocol.Incoming.Join -> onPeerJoined(m.id)
            is RelayProtocol.Incoming.Leave -> dropPeer(RelayProtocol.hex(m.id))
            is RelayProtocol.Incoming.Data -> onData(m.src, m.payload, receivedAt)
            null -> Unit
        }
    }

    private fun onPeerJoined(id: Int) {
        if (closed || id == selfRelayId) return
        val key = RelayProtocol.hex(id)
        // Someone who left and came straight back has a new id; the same id again means our
        // table is stale, so start that pair over.
        if (endpoints.containsKey(key)) dropPeer(key)
        if (throttle.isLocked || endpoints.size >= MAX_PEERS) return
        val e = Endpoint(id, key)
        endpoints[key] = e
        val ch = SecureChannel(
            code = code,
            sessionId = sessionId,
            seenNonces = seenNonces,
            send = { bytes -> sendRaw(id, bytes) },
            worker = worker,
            timer = timer,
            onResult = { r -> onAuthResult(key, r) },
            iterations = iterations,
        )
        e.channel = ch
        statusFlow.value = "Securing connection"
        ch.start()
    }

    private fun onData(src: Int, payload: ByteArray, receivedAt: Long) {
        val e = endpoints[RelayProtocol.hex(src)] ?: return
        val ch = e.channel ?: return
        val m = FrameBytes.parse(payload) ?: return
        val plain = ch.receive(m) ?: return
        onMessage(plain)
        listener?.onFrame(e.key, plain, receivedAt)
    }

    private fun onAuthResult(key: String, r: SecureChannel.Result) {
        val e = endpoints[key] ?: return
        when (r) {
            SecureChannel.Result.Authenticated -> {
                throttle.recordSuccess()
                e.ready = true
                refreshState()
                listener?.onEndpointReady(key)
            }
            SecureChannel.Result.WrongCode -> {
                throttle.recordFailure()
                dropPeer(key)
                listener?.onWrongCode()
            }
            is SecureChannel.Result.Failed -> {
                Log.w(TAG, "handshake with $key failed: ${r.reason}")
                dropPeer(key)
            }
        }
    }

    private fun dropPeer(key: String) {
        val e = endpoints.remove(key) ?: return
        e.channel?.close()
        refreshState()
        if (e.ready) listener?.onEndpointGone(key)
    }

    private fun onSocketClosed(failure: RelayFailure) {
        socket = null
        relayUp = false
        for (key in endpoints.keys.toList()) dropPeer(key)
        if (closed) return
        if (failure == RelayFailure.Refused) {
            giveUp("The relay address was refused")
            return
        }
        stateFlow.value = LinkState.Reconnecting
        statusFlow.value = when (failure) {
            RelayFailure.Full -> "The room is full"
            RelayFailure.Busy -> "The relay is busy"
            else -> "Lost the connection - reconnecting"
        }
        val delay = backoff(attempt++, failure)
        if (!timer.isShutdown) {
            timer.schedule({ if (!closed) worker.execute { connectNow() } }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun giveUp(why: String) {
        if (closed) return
        gaveUp = true
        stateFlow.value = LinkState.Failed
        statusFlow.value = why
    }

    // ------------------------------------------------------------ MeshPort / PeerLink send

    override fun readyEndpoints(): Collection<String> = endpoints.values.filter { it.ready }.map { it.key }

    override fun sendTo(endpoint: String, type: Byte, flags: Byte, payload: ByteArray): Boolean {
        val e = endpoints[endpoint] ?: return false
        if (!e.ready) return false
        val framed = e.channel?.seal(type, flags, payload) ?: return false
        return sendRaw(e.id, framed)
    }

    private fun sendRaw(id: Int, bytes: ByteArray): Boolean {
        if (bytes.size > maxPayload) return false
        val s = socket ?: return false
        return s.send(RelayProtocol.send(id, bytes))
    }

    /** PeerLink contract: a framed message, sent to every authenticated rider. */
    override fun send(message: ByteArray): Boolean {
        val m = FrameBytes.parse(message) ?: return false
        var any = false
        for (id in readyEndpoints()) if (sendTo(id, m.type, m.flags, m.payload)) any = true
        return any
    }

    override fun setOnMessage(handler: (Message) -> Unit) { onMessage = handler }

    private fun refreshState() {
        if (closed || gaveUp) return
        val n = readyEndpoints().size
        if (n > 0) {
            stateFlow.value = LinkState.Connected
            statusFlow.value = if (n == 1) "Connected to 1 rider" else "Connected to $n riders"
        } else if (relayUp) {
            stateFlow.value = LinkState.Listening
            statusFlow.value = "Looking for riders"
        }
    }

    companion object {
        private const val TAG = "InternetLink"

        /** The most riders in one room; the relay enforces the same limit. */
        const val MAX_PEERS = 11
        private const val DEFAULT_MAX_PAYLOAD = 2048
        private const val MAX_PAYLOAD_CAP = 16 * 1024
    }
}
