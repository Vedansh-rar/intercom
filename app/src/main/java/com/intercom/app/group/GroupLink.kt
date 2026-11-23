package com.intercom.app.group

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.Message
import com.intercom.app.security.AuthThrottle
import com.intercom.app.security.NonceCache
import com.intercom.app.security.RideCode
import com.intercom.app.security.SecureChannel
import com.intercom.app.transport.LinkState
import com.intercom.app.transport.PeerLink
import com.intercom.app.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Group-ride transport: Google Nearby Connections with [Strategy.P2P_CLUSTER], so every
 * phone advertises and discovers at once and the riders form a cluster rather than a star.
 *
 * Nearby is used **for transport only**. Nothing about who may join rests on it: the
 * endpoint name carries the rider's display name and random ID and no secret, and
 * Nearby's own pairing digits are not consulted. Every connection runs the Phase 3
 * handshake ([SecureChannel]) and until it succeeds all frames except the handshake itself
 * are dropped; a failed handshake disconnects the endpoint.
 *
 * ## PeerLink, and where this deviates from it
 * [PeerLink] is shaped for one buddy: one state, one inbound message stream. A group has
 * many peers, and per-rider decoding needs to know *which* neighbour a frame came from.
 * So this implements [PeerLink] for the lifecycle ([state], [status], [start], [close]) and
 * for [send], which here means "broadcast to every authenticated neighbour"; but frames
 * come out through [listener], which names the endpoint, not through [setOnMessage]. The
 * audio pipeline does not change; the group call wires the mixer instead of one buffer.
 *
 * ## Threads
 * Nearby calls its callbacks on the main thread. Anything slow or stateful is moved off it:
 * received payloads to a single-thread executor (also what keeps [FrameCipher]'s replay
 * window single-threaded), key derivation to a worker pool, resends to a timer. The
 * endpoint table is a [ConcurrentHashMap].
 *
 * Needs Google Play services. [isAvailable] says whether this phone has it.
 */
class GroupLink(
    context: Context,
    private val selfName: String,
    private val selfId: Int,
    private val sessionId: Long,
    private val code: RideCode,
    private val throttle: AuthThrottle = AuthThrottle(),
    private val seenNonces: NonceCache = NonceCache(),
) : PeerLink, MeshPort {

    interface Listener {
        /** A decrypted frame from an authenticated neighbour. Called on the receive thread. */
        fun onFrame(endpoint: String, message: Message, receivedAt: Long)

        /** A neighbour finished the handshake; frames can flow both ways. */
        fun onEndpointReady(endpoint: String)

        /** A neighbour went away (or failed to authenticate). */
        fun onEndpointGone(endpoint: String)

        /** The handshake with someone failed because their code differs from ours. */
        fun onWrongCode()
    }

    @Volatile var listener: Listener? = null

    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private class Endpoint(val id: String, val untrustedName: String, val incoming: Boolean) {
        @Volatile var channel: SecureChannel? = null
        @Volatile var ready = false
    }

    private class Found(val name: String, val peerId: Int?, val seenAt: Long, @Volatile var lastTryAt: Long = 0L)

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private val found = ConcurrentHashMap<String, Found>()

    private val rx = Executors.newSingleThreadExecutor(ThreadFactory { r -> Thread(r, "group-rx") })
    private val worker = Executors.newFixedThreadPool(2, ThreadFactory { r -> Thread(r, "group-auth") })
    private val timer = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "group-timer") })

    private val stateFlow = MutableStateFlow(LinkState.Idle)
    private val statusFlow = MutableStateFlow("Not started")
    private val rttFlow = MutableStateFlow(-1)
    private val helloFlow = MutableStateFlow<Hello?>(null)

    override val transport: Transport = Transport.Nearby
    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val rttMillis: StateFlow<Int> = rttFlow.asStateFlow()
    override val peerHello: StateFlow<Hello?> = helloFlow.asStateFlow()
    override val status: StateFlow<String> = statusFlow.asStateFlow()
    override val clockOffsetMicros: Long = 0L

    @Volatile private var started = false
    @Volatile private var closed = false
    private var onMessage: (Message) -> Unit = {}

    // ------------------------------------------------------------ lifecycle

    override fun start() {
        if (started) return
        started = true
        if (!isAvailable(appContext)) {
            stateFlow.value = LinkState.Failed
            statusFlow.value = "Group mode needs Google Play services"
            return
        }
        stateFlow.value = LinkState.Listening
        statusFlow.value = "Looking for riders"

        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            endpointName(), SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { fail("Could not advertise: ${it.message}") }

        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { fail("Could not search for riders: ${it.message}") }

        timer.scheduleWithFixedDelay({ maintain() }, MAINTAIN_MS, MAINTAIN_MS, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        for (e in endpoints.values) e.channel?.close()
        endpoints.clear()
        found.clear()
        timer.shutdownNow()
        worker.shutdownNow()
        rx.shutdown()
        stateFlow.value = LinkState.Closed
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        if (!closed) {
            stateFlow.value = LinkState.Failed
            statusFlow.value = msg
        }
    }

    // ------------------------------------------------------------ MeshPort / PeerLink send

    override fun readyEndpoints(): Collection<String> =
        endpoints.values.filter { it.ready }.map { it.id }

    override fun sendTo(endpoint: String, type: Byte, flags: Byte, payload: ByteArray): Boolean {
        val e = endpoints[endpoint] ?: return false
        if (!e.ready) return false
        val framed = e.channel?.seal(type, flags, payload) ?: return false
        client.sendPayload(endpoint, Payload.fromBytes(framed))
        return true
    }

    /** PeerLink contract: a framed message, sent to every authenticated neighbour. */
    override fun send(message: ByteArray): Boolean {
        val m = FrameBytes.parse(message) ?: return false
        var any = false
        for (id in readyEndpoints()) if (sendTo(id, m.type, m.flags, m.payload)) any = true
        return any
    }

    /** Kept for the interface; group consumers use [listener], which names the neighbour. */
    override fun setOnMessage(handler: (Message) -> Unit) { onMessage = handler }

    // ------------------------------------------------------------ discovery

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val (_, peerId) = GroupEndpointName.parse(info.endpointName)
            found[endpointId] = Found(info.endpointName, peerId, System.currentTimeMillis())
            // The phone with the lower ID asks first, so two phones that see each other at
            // the same moment do not both request. The other asks later, in maintain(), if
            // nothing has happened - covering the case where only one side can see the other.
            if (peerId != null && GroupEndpointName.shouldInitiate(selfId, peerId)) tryRequest(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
        }
    }

    private fun tryRequest(endpointId: String) {
        if (closed || endpoints.containsKey(endpointId)) return
        if (endpoints.size >= MAX_DIRECT) return
        if (throttle.isLocked) return
        val f = found[endpointId] ?: return
        f.lastTryAt = System.currentTimeMillis()
        client.requestConnection(endpointName(), endpointId, lifecycle)
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                // Already connected or a request already in flight is fine; anything else is
                // retried by maintain() after the back-off.
                if (code != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    Log.d(TAG, "request to $endpointId failed: ${e.message}")
                }
            }
    }

    /** Periodic: retry unreached endpoints, and let the higher-ID side ask once it is overdue. */
    private fun maintain() {
        if (closed) return
        val t = System.currentTimeMillis()
        for ((id, f) in found) {
            if (endpoints.containsKey(id)) continue
            if (t - f.lastTryAt < RETRY_MS) continue
            val ours = f.peerId != null && GroupEndpointName.shouldInitiate(selfId, f.peerId)
            if (ours || t - f.seenAt >= FALLBACK_REQUEST_MS) tryRequest(id)
        }
    }

    // ------------------------------------------------------------ connections

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (closed) return
            if (throttle.isLocked || endpoints.size >= MAX_DIRECT || endpoints.containsKey(endpointId)) {
                client.rejectConnection(endpointId)
                return
            }
            endpoints[endpointId] = Endpoint(endpointId, info.endpointName, info.isIncomingConnection)
            // Accepting only opens the pipe. The digits Nearby offers are not used; trust
            // comes from our own handshake, run as soon as the connection result is OK.
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val e = endpoints[endpointId] ?: return
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                endpoints.remove(endpointId)
                return
            }
            val ch = SecureChannel(
                code = code,
                sessionId = sessionId,
                seenNonces = seenNonces,
                send = { bytes -> client.sendPayload(endpointId, Payload.fromBytes(bytes)); true },
                worker = worker,
                timer = timer,
                onResult = { r -> onAuthResult(endpointId, r) },
            )
            e.channel = ch
            statusFlow.value = "Securing connection"
            ch.start()
        }

        override fun onDisconnected(endpointId: String) {
            val e = endpoints.remove(endpointId) ?: return
            e.channel?.close()
            refreshState()
            if (e.ready) listener?.onEndpointGone(endpointId)
        }
    }

    private fun onAuthResult(endpointId: String, r: SecureChannel.Result) {
        val e = endpoints[endpointId] ?: return
        when (r) {
            SecureChannel.Result.Authenticated -> {
                throttle.recordSuccess()
                e.ready = true
                refreshState()
                listener?.onEndpointReady(endpointId)
            }
            SecureChannel.Result.WrongCode -> {
                throttle.recordFailure()
                drop(endpointId)
                listener?.onWrongCode()
            }
            is SecureChannel.Result.Failed -> {
                Log.w(TAG, "handshake with $endpointId failed: ${r.reason}")
                drop(endpointId)
            }
        }
    }

    private fun drop(endpointId: String) {
        val e = endpoints.remove(endpointId)
        e?.channel?.close()
        runCatching { client.disconnectFromEndpoint(endpointId) }
        refreshState()
        if (e?.ready == true) listener?.onEndpointGone(endpointId)
    }

    // ------------------------------------------------------------ payloads

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val at = System.currentTimeMillis()
            // Off the main thread at once: Nearby calls this there, and decrypting and
            // routing do not belong on it.
            if (!rx.isShutdown) rx.execute { handle(endpointId, bytes, at) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun handle(endpointId: String, bytes: ByteArray, receivedAt: Long) {
        val e = endpoints[endpointId] ?: return
        val ch = e.channel ?: return
        val m = FrameBytes.parse(bytes) ?: return
        // Handshake frames go to the worker inside; anything else only comes out if the
        // channel is authenticated and the frame decrypts. Everything else is dropped here.
        val plain = ch.receive(m) ?: return
        onMessage(plain)
        listener?.onFrame(endpointId, plain, receivedAt)
    }

    private fun refreshState() {
        if (closed) return
        val n = readyEndpoints().size
        if (n > 0) {
            stateFlow.value = LinkState.Connected
            statusFlow.value = if (n == 1) "Connected to 1 rider" else "Connected to $n riders"
        } else if (stateFlow.value != LinkState.Failed) {
            stateFlow.value = LinkState.Listening
            statusFlow.value = "Looking for riders"
        }
    }

    // ------------------------------------------------------------ helpers

    /** Display name and random ID, joined by '#'. Carries no secret. */
    private fun endpointName(): String = GroupEndpointName.format(selfName, selfId)

    companion object {
        private const val TAG = "GroupLink"

        /** Isolates this app's riders from any other Nearby app; version in the string. */
        const val SERVICE_ID = "com.intercom.app.group.v1"

        /** Direct connections per phone; further riders are reached through relays. */
        const val MAX_DIRECT = 6
        private const val MAINTAIN_MS = 3_000L
        private const val RETRY_MS = 5_000L
        private const val FALLBACK_REQUEST_MS = 4_000L

        fun isAvailable(context: Context): Boolean =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}
