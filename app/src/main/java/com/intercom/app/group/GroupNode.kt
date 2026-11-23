package com.intercom.app.group

import com.intercom.app.location.PositionTracker
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.PeerInfo
import com.intercom.app.protocol.SosAlert
import com.intercom.app.protocol.PositionReport
import com.intercom.app.protocol.Protocol

/** What [GroupNode] needs from the radio: who is directly connected, and a way to send. */
interface MeshPort {
    /** Endpoints that are connected AND authenticated. */
    fun readyEndpoints(): Collection<String>

    /** Sends one message to one neighbour, sealed for that neighbour. False if it cannot. */
    fun sendTo(endpoint: String, type: Byte, flags: Byte, payload: ByteArray): Boolean
}

/**
 * Everything a group ride does with traffic, minus the radio: it receives decrypted frames
 * from neighbours, runs the relay rules, dispatches audio / floor / peer messages to the
 * mixer, [GroupFloor] and [PeerTable], and originates this phone's own messages.
 *
 * It exists as a class of its own so that a whole convoy can be simulated in a unit test
 * with in-memory [MeshPort]s: exactly-once delivery, TTL limits, loops and a neighbour
 * dropping out are all checked there, because they cannot be checked without several
 * phones on a road.
 *
 * Threads: [onFrame] on the link's receive thread; [sendAudio] and the floor calls on the
 * capture thread; [tick] on a timer. All shared state lives in thread-safe members.
 */
class GroupNode(
    val selfId: Int,
    private val selfName: String,
    selfPriority: Int,
    val port: MeshPort,
    val mixer: GroupMixer,
    val peers: PeerTable = PeerTable(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Everyone's shared position, keyed by sender ID. Stays empty unless riders share. */
    val positions: PositionTracker = PositionTracker(now),
) {
    val router = MeshRouter(selfId)
    val floor = GroupFloor(selfId, selfPriority)
    private val priority = selfPriority

    private val audioSeq = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lastBeaconAt = 0L

    /** The local rider lost the floor to a higher-priority speaker; stop transmitting. */
    @Volatile var onYield: () -> Unit = {}

    /** A waiting rider can speak now. */
    @Volatile var onChannelFree: () -> Unit = {}

    /** A rider appeared or vanished, or the floor changed: refresh the screen. */
    @Volatile var onChanged: () -> Unit = {}

    /**
     * Whether positions addressed to this rider are kept. The call turns it off while "Share
     * my location" is off, so a phone that has not opted in stores nobody's location. Relaying
     * is unaffected: a position still passes through on its way to riders who do want it.
     */
    @Volatile var positionsEnabled: Boolean = true

    /**
     * Whether this phone forwards other riders' messages. On for the over-the-air mesh,
     * where a rider may only reach the one behind through a neighbour. Off for an internet
     * ride: the relay already connects every rider to every other, so a forward would reach
     * riders who already have the message and only spend the rider's mobile data.
     */
    @Volatile var relayEnabled: Boolean = true

    /**
     * An SOS from another rider: their sender ID, name if known, and the alert. Called for
     * every copy that reaches this phone (the mesh drops exact duplicates, but a resend is a
     * new message), so the receiver deduplicates.
     */
    @Volatile var onSos: (senderId: Int, name: String?, alert: SosAlert) -> Unit = { _, _, _ -> }

    @Volatile var relayed = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var droppedStale = 0L; private set

    // ------------------------------------------------------------ receive

    /**
     * A decrypted frame from the neighbour on [from]. Anything that is not a mesh envelope
     * is a stray (a two-rider message from a confused peer) and is ignored.
     * [receivedAt] is when it came off the radio, so time spent queued counts as age.
     */
    fun onFrame(from: String, m: Message, receivedAt: Long = now()) {
        if (m.type != Protocol.TYPE_MESH) return
        val env = MeshEnvelope.parse(m.payload) ?: run { dropped++; return }

        peers.heard(env.senderId, env.hopsTravelled, from)
        val residence = (now() - receivedAt).coerceIn(0, 0xFFFF).toInt()
        val decision = router.onReceive(env, residence)

        if (decision.deliver) deliver(env)
        else if (decision.dropReason == "audio too old") droppedStale++
        else dropped++

        val relay = decision.relay
        if (relay != null && relayEnabled) {
            val bytes = relay.toBytes()
            for (ep in port.readyEndpoints()) {
                if (ep == from) continue
                if (port.sendTo(ep, Protocol.TYPE_MESH, 0, bytes)) relayed++
            }
        }
    }

    private fun deliver(env: MeshEnvelope) {
        val t = now()
        when (env.innerType) {
            // A rider muted on this phone is not heard and does not take the floor, so they
            // cannot duck everyone else while saying nothing audible. Their messages are
            // still relayed (see onFrame): mute is about this phone's speaker only.
            Protocol.TYPE_AUDIO, Protocol.TYPE_FLOOR_TAKEN -> if (peers.isMuted(env.senderId)) Unit else deliverVoice(env, t)
            Protocol.TYPE_FLOOR_RELEASE -> {
                val e = floor.remoteReleased(env.senderId)
                if (e is GroupFloor.Event.ChannelFree) onChannelFree()
            }
            Protocol.TYPE_PEER_INFO -> {
                PeerInfo.parse(env.body)?.let { peers.info(env.senderId, it.name, it.priority) }
            }
            // Positions are not audio, so a muted rider is still tracked: mute is about this
            // phone's speaker, not about knowing where someone is.
            Protocol.TYPE_POSITION -> if (positionsEnabled) {
                PositionReport.parse(env.body)?.let { positions.onReport(env.senderId, it, env.ageMs.toLong()) }
            }
            // An SOS is for everyone, so it is delivered even from a rider muted on this phone.
            Protocol.TYPE_SOS -> SosAlert.parse(env.body)?.let {
                onSos(env.senderId, peers.nameOf(env.senderId), it)
            }
            else -> Unit // anything newer: ignored
        }
        onChanged()
    }

    private fun deliverVoice(env: MeshEnvelope, t: Long) {
        when (env.innerType) {
            Protocol.TYPE_AUDIO -> {
                val prio = peers.priorityOf(env.senderId)
                val r = floor.remoteClaim(env.senderId, prio, peers.nameOf(env.senderId), t)
                if (r == GroupFloor.RemoteResult.LocalYielded) onYield()
                val seq = GroupAudio.seq(env.body)
                if (seq >= 0) {
                    mixer.put(env.senderId, seq, env.innerFlags.toInt() and 0x7F, GroupAudio.payload(env.body))
                }
            }
            Protocol.TYPE_FLOOR_TAKEN -> {
                val prio = if (env.body.isNotEmpty()) env.body[0].toInt() and 0xFF else peers.priorityOf(env.senderId)
                // The claim carries the priority; remember it so the audio that follows agrees.
                peers.info(env.senderId, peers.nameOf(env.senderId), prio)
                val r = floor.remoteClaim(env.senderId, prio, peers.nameOf(env.senderId), t)
                if (r == GroupFloor.RemoteResult.LocalYielded) onYield()
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------ transmit

    /** Sends [body] as our own envelope to every neighbour. Returns neighbours reached. */
    private fun originate(innerType: Byte, innerFlags: Byte, body: ByteArray): Int {
        val bytes = router.originate(innerType, innerFlags, body).toBytes()
        var n = 0
        for (ep in port.readyEndpoints()) if (port.sendTo(ep, Protocol.TYPE_MESH, 0, bytes)) n++
        return n
    }

    /** One encoded audio frame. Only sent while this rider holds the floor. */
    fun sendAudio(codecFlag: Byte, payload: ByteArray, length: Int = payload.size): Boolean {
        if (!floor.selfHolds()) return false
        val body = GroupAudio.pack(audioSeq.getAndIncrement() and 0xFFFF, payload, length)
        return originate(Protocol.TYPE_AUDIO, codecFlag, body) > 0
    }

    /**
     * The local rider is speaking. Returns the floor decision; the moments the floor is
     * newly taken are announced to the group.
     */
    fun localSpeaking(): GroupFloor.LocalResult {
        val r = floor.localSpeaking(now(), selfName)
        if (r == GroupFloor.LocalResult.NewlyGranted || r == GroupFloor.LocalResult.Preempted) {
            originate(Protocol.TYPE_FLOOR_TAKEN, 0, byteArrayOf(priority.toByte()))
            onChanged()
        }
        return r
    }

    fun localStopped() {
        if (floor.localStopped()) {
            originate(Protocol.TYPE_FLOOR_RELEASE, 0, ByteArray(0))
            onChanged()
        }
    }

    /** Sends our position to the group (relayed like everything else). True if anyone got it. */
    fun sendPosition(report: PositionReport): Boolean =
        originate(Protocol.TYPE_POSITION, 0, report.toBytes()) > 0

    /** Sends an SOS to the group (relayed like everything else). True if anyone got it. */
    fun sendSos(alert: SosAlert): Boolean = originate(Protocol.TYPE_SOS, 0, alert.toBytes()) > 0

    /** Announces this phone's name and priority to the group. */
    fun beacon() {
        lastBeaconAt = now()
        originate(Protocol.TYPE_PEER_INFO, 0, PeerInfo(PeerInfo.Status.Ok, selfName, priority).toBytes())
    }

    // ------------------------------------------------------------ housekeeping

    /** About every 100 ms. */
    fun tick(pushToTalk: Boolean) {
        val t = now()
        var changed = false

        for (id in peers.expire()) { forget(id); changed = true }

        when (floor.tick(t, pushToTalk)) {
            is GroupFloor.Event.ChannelFree -> { onChannelFree(); changed = true }
            is GroupFloor.Event.Released -> changed = true
            null -> Unit
        }
        if (t - lastBeaconAt >= BEACON_MS && port.readyEndpoints().isNotEmpty()) beacon()
        if (changed) onChanged()
    }

    /** A direct connection went away: everyone who was reached through it. */
    fun onEndpointLost(endpoint: String) {
        for (id in peers.removeVia(endpoint)) forget(id)
        onChanged()
    }

    /** A new neighbour is ready: tell them who we are straight away. */
    fun onEndpointReady() = beacon()

    private fun forget(id: Int) {
        mixer.remove(id)
        router.forget(id)
        positions.forget(id)
        val e = floor.peerLeft(id)
        if (e is GroupFloor.Event.ChannelFree) onChannelFree()
    }

    fun setPeerMuted(id: Int, muted: Boolean) {
        peers.setMuted(id, muted)
        mixer.setMuted(id, muted)
        onChanged()
    }

    /** The list the screen draws. */
    fun snapshot(): List<GroupPeer> {
        val where = positions.snapshot()
        return peers.snapshot(
            speaking = mixer::speaking,
            lossPercent = mixer::lossPercent,
            hasLossData = { mixer.playedFrames(it) >= 25 },
            distance = { where[it] },
        )
    }

    companion object {
        const val BEACON_MS = 5_000L
    }
}
