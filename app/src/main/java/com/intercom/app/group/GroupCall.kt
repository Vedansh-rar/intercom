package com.intercom.app.group

import android.content.Context
import com.intercom.app.audio.AdpcmAudioCodec
import com.intercom.app.audio.AudioCodec
import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.Beeper
import com.intercom.app.audio.NativeOpusEngine
import com.intercom.app.audio.OpusCodec
import com.intercom.app.floor.Vad
import com.intercom.app.floor.VadEvent
import com.intercom.app.media.FocusManager
import com.intercom.app.protocol.Hello
import com.intercom.app.relay.OkHttpRelayConnector
import com.intercom.app.relay.RelayUrl
import com.intercom.app.security.AuthThrottle
import com.intercom.app.security.NonceCache
import com.intercom.app.security.RideCode
import com.intercom.app.transport.LinkState
import com.intercom.app.util.Prefs
import com.intercom.app.util.TalkMode
import kotlinx.coroutines.flow.StateFlow

/**
 * One group ride, from this phone: the Nearby link, the mesh node, the mixer, and the
 * capture side (detector, floor, encoder).
 *
 * It owns what is specific to a convoy so that [com.intercom.app.call.CallController]
 * stays what it was for two riders; the controller starts it, feeds it mic frames, ticks
 * it and reads its snapshot.
 *
 * Group audio is sent at a fixed, modest rate with no adaptation: there is no single
 * link to measure, and every extra hop costs airtime.
 */
class GroupCall(
    context: Context,
    private val prefs: Prefs,
    code: RideCode,
    val priority: Int,
    private val opusAvailable: Boolean,
    private val focus: FocusManager,
    private val beeper: Beeper,
    throttle: AuthThrottle,
    seenNonces: NonceCache,
    private val onChanged: () -> Unit,
    /** `wss://` relay base for an internet ride; null for the over-the-air group. */
    private val internetRelay: String? = null,
) {
    val selfId: Int = SenderId.random()
    private val sessionId = Hello.newSessionId()
    private val selfName = prefs.current.riderName.ifBlank { PeerTable.DEFAULT_NAME }

    private var nodeRef: GroupNode? = null

    val link: GroupTransport = if (internetRelay != null) {
        InternetLink(
            sessionId = sessionId,
            code = code,
            connectorFor = { room -> OkHttpRelayConnector(RelayUrl.endpoint(internetRelay, room)) },
            throttle = throttle,
            seenNonces = seenNonces,
        )
    } else {
        GroupLink(context, selfName, selfId, sessionId, code, throttle, seenNonces)
    }

    val mixer = GroupMixer(
        newDecoder = ::newDecoder,
        duckFactor = { id -> nodeRef?.floor?.duckFactor(id) ?: 1f },
    )

    val node = GroupNode(selfId, selfName, priority, link, mixer).also {
        nodeRef = it
        // Over the internet every rider already reaches every other directly.
        it.relayEnabled = internetRelay == null
    }

    val linkState: StateFlow<LinkState> get() = link.state
    val linkStatus: StateFlow<String> get() = link.status

    private val vad = Vad(prefs.current.vadSensitivity)
    private val encoder: AudioCodec = newEncoder()
    private val txBuf = ByteArray(AudioCodec.MAX_ENCODED_BYTES)
    private val preroll = ArrayDeque<ShortArray>(AudioConstants.PREROLL_FRAMES)

    /** True once someone's code did not match ours; cleared when anyone authenticates. */
    @Volatile var sawWrongCode = false; private set

    @Volatile var pushToTalkDown = false; private set
    private var vadOpen = false
    @Volatile private var wasWaiting = false

    init {
        link.listener = object : GroupLink.Listener {
            override fun onFrame(endpoint: String, message: com.intercom.app.protocol.Message, receivedAt: Long) =
                node.onFrame(endpoint, message, receivedAt)

            override fun onEndpointReady(endpoint: String) {
                sawWrongCode = false
                node.onEndpointReady()
                onChanged()
            }

            override fun onEndpointGone(endpoint: String) = node.onEndpointLost(endpoint)

            override fun onWrongCode() {
                sawWrongCode = true
                onChanged()
            }
        }
        node.onChanged = {
            // Music is ducked while another rider holds the floor (and while this one does, if
            // the rider asked), and let go when it is free.
            focus.applyFloor(node.floor.state())
            onChanged()
        }
        node.onChannelFree = { if (prefs.current.beepWhenMuted) beeper.request() }
        node.onYield = { wasWaiting = true }
    }

    fun start() {
        vad.sensitivity = prefs.current.vadSensitivity
        link.start()
    }

    fun stop() {
        link.close()
        mixer.release()
        encoder.release()
        preroll.clear()
    }

    /** Applies the rider's detector setting live. */
    fun applySettings() {
        vad.sensitivity = prefs.current.vadSensitivity
    }

    fun setPushToTalk(down: Boolean) { pushToTalkDown = down }

    /** The rider muted their mic: stop talking now and forget anything captured before. */
    fun stopTalkingForMute() {
        pushToTalkDown = false
        vadOpen = false
        preroll.clear()
        vad.reset()
        node.localStopped()
    }

    /** Sends an SOS to the group. True if at least one neighbour got it. */
    fun sendSos(alert: com.intercom.app.protocol.SosAlert): Boolean = node.sendSos(alert)

    /** After the audio route was rebuilt: nothing captured before it is worth sending. */
    fun resetCapture() {
        vad.reset()
        preroll.clear()
        vadOpen = false
        node.localStopped()
    }

    // ------------------------------------------------------------ send path

    /** Called on the capture thread, once per 20 ms. */
    fun onMicFrame(frame: ShortArray) {
        val event = vad.process(frame)

        // Nobody connected: keep the detector's noise floor current but take no floor,
        // and encode nothing into the void.
        if (node.port.readyEndpoints().isEmpty()) {
            // Everyone left while we held the floor: give it up, or we would stay the
            // holder (a local holder is never stale) when someone next connects.
            if (node.floor.selfHolds()) node.localStopped()
            wasWaiting = false
            remember(frame)
            return
        }

        val open = prefs.current.talkMode == TalkMode.Open
        if (open) {
            when (event) {
                VadEvent.Open -> vadOpen = true
                VadEvent.Close -> vadOpen = false
                VadEvent.None -> Unit
            }
        } else {
            vadOpen = false
        }
        val wantsToTalk = if (open) vadOpen else pushToTalkDown

        if (!wantsToTalk) {
            if (node.floor.selfHolds()) node.localStopped()
            wasWaiting = false
            remember(frame)
            return
        }

        when (node.localSpeaking()) {
            GroupFloor.LocalResult.NewlyGranted, GroupFloor.LocalResult.Preempted -> {
                wasWaiting = false
                focus.duckForLocal()
                flushPreroll()
                send(frame)
            }
            GroupFloor.LocalResult.Granted -> send(frame)
            GroupFloor.LocalResult.Waiting -> {
                // Someone else has the floor and outranks or precedes us: queued, not sent.
                if (!wasWaiting && prefs.current.beepWhenMuted) beeper.request()
                wasWaiting = true
                remember(frame)
            }
        }
    }

    private fun send(frame: ShortArray) {
        val n = encoder.encode(frame, txBuf)
        if (n <= 0) return
        node.sendAudio(encoder.wireFlag, txBuf, n)
    }

    private fun remember(frame: ShortArray) {
        if (preroll.size >= AudioConstants.PREROLL_FRAMES) preroll.removeFirst()
        preroll.addLast(frame.copyOf())
    }

    private fun flushPreroll() {
        while (preroll.isNotEmpty()) send(preroll.removeFirst())
    }

    /** About every 200 ms. */
    fun tick() {
        node.tick(prefs.current.talkMode == TalkMode.PushToTalk)
    }

    fun peers(): List<GroupPeer> = node.snapshot()

    /** The current floor holder's name when it is somebody else, for the status line. */
    fun speakerName(): String? =
        if (node.floor.selfHolds()) null else node.floor.holderName()

    // ------------------------------------------------------------ codecs

    private fun newEncoder(): AudioCodec {
        if (opusAvailable) {
            val engine = NativeOpusEngine(GROUP_BITRATE)
            if (engine.usable) return OpusCodec(engine, GROUP_BITRATE)
            engine.release()
        }
        return AdpcmAudioCodec()
    }

    /** A decoder for the codec flag in a rider's frames; null if this phone cannot run it. */
    private fun newDecoder(flag: Int): AudioCodec? = when (flag) {
        0 -> AdpcmAudioCodec()
        1 -> if (!opusAvailable) null else {
            val engine = NativeOpusEngine(GROUP_BITRATE)
            if (engine.usable) OpusCodec(engine, GROUP_BITRATE) else { engine.release(); null }
        }
        else -> null
    }

    companion object {
        /** Fixed Opus rate on the group link: intelligible speech, small enough to relay. */
        const val GROUP_BITRATE = 16_000
    }
}
