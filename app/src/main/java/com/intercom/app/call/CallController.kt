package com.intercom.app.call

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.intercom.app.audio.AdpcmAudioCodec
import com.intercom.app.audio.AudioCodec
import com.intercom.app.audio.AudioConstants
import com.intercom.app.audio.Beeper
import com.intercom.app.audio.BitratePolicy
import com.intercom.app.audio.CodecChoice
import com.intercom.app.audio.JitterBuffer
import com.intercom.app.audio.MicCapture
import com.intercom.app.audio.NativeBridge
import com.intercom.app.audio.NativeOpusEngine
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.audio.OpusCodec
import com.intercom.app.audio.VoicePlayer
import com.intercom.app.floor.FloorActions
import com.intercom.app.floor.FloorArbiter
import com.intercom.app.floor.FloorState
import com.intercom.app.floor.Vad
import com.intercom.app.floor.VadEvent
import com.intercom.app.media.FocusManager
import com.intercom.app.protocol.AuthChallenge
import com.intercom.app.protocol.AuthResponse
import com.intercom.app.protocol.Codec
import com.intercom.app.protocol.Capabilities
import com.intercom.app.protocol.CodecId
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.NegotiatedFeatures
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.Protocol
import com.intercom.app.security.AuthSession
import com.intercom.app.security.ChannelSecurity
import com.intercom.app.security.FrameGate
import com.intercom.app.security.SecurityPolicy
import com.intercom.app.security.AuthThrottle
import com.intercom.app.security.FrameCipher
import com.intercom.app.security.NonceCache
import com.intercom.app.security.RideCode
import com.intercom.app.transport.LinkRole
import com.intercom.app.transport.LinkState
import com.intercom.app.transport.PeerLink
import com.intercom.app.transport.LinkSelector
import com.intercom.app.transport.Transport
import com.intercom.app.util.Permissions
import com.intercom.app.util.Prefs
import com.intercom.app.util.TalkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The session: it owns the link, the audio pipeline and the floor, and it is the only
 * place where the three meet.
 *
 * Send path:  mic -> detector -> arbiter -> encoder -> link
 * Receive path: link -> decoder -> jitter buffer -> track tagged as media
 *
 * The pipeline never learns which transport is underneath; it talks to [PeerLink].
 */
class CallController(
    private val context: Context,
    private val prefs: Prefs,
    private val adapter: BluetoothAdapter,
) {
    companion object {
        private const val TAG = "CallController"
        /** Arbiter watchdogs and the live mic level run on this. */
        private const val TICK_MS = 200L
        /** The full status snapshot only needs to change once a second. */
        private const val STATUS_EVERY_TICKS = 5L
        private const val STATS_TAG = "IntercomStats"
        /** 25 ticks at 200 ms: one stats line every 5 seconds. */
        private const val STATS_EVERY_TICKS = 25L

        /** The challenge is re-sent until answered: the buddy may not have been ready for the first. */
        private const val AUTH_RESEND_MS = 700L
        private const val AUTH_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(CallStatus())
    val status: StateFlow<CallStatus> = _status.asStateFlow()

    /**
     * The microphone level, kept out of [CallStatus] on purpose.
     *
     * It changes several times a second, and while it lived in the one big status object
     * every tick invalidated every composable reading any field of it - the whole
     * scrolling screen, the dial included. Split out, only the level meter recomposes.
     */
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val jitter = JitterBuffer()
    private val beeper = Beeper()
    private val player = VoicePlayer(jitter, beeper)
    private val vad = Vad(prefs.current.vadSensitivity)

    /**
     * The voice codec for this call. ADPCM until the buddy's HELLO says otherwise; swapped
     * to Opus when both phones advertised it. Frames carry the codec in their flags byte
     * so a mismatch is dropped, never decoded into noise.
     */
    @Volatile private var codec: AudioCodec = AdpcmAudioCodec()
    private val txBuf = ByteArray(AudioCodec.MAX_ENCODED_BYTES)
    private val bitratePolicy = BitratePolicy()
    private var prevLost = 0L
    private var prevPlayed = 0L
    @Volatile private var codecMismatches = 0L

    /** Whether libopus loaded and can create coder state on this phone. */
    private val opusAvailable: Boolean by lazy {
        if (!NativeBridge.available) false else runCatching {
            val probe = NativeOpusEngine(OpusCodec.DEFAULT_BITRATE)
            val ok = probe.usable
            probe.release()
            ok
        }.getOrDefault(false)
    }

    private var mic: MicCapture? = null

    /**
     * What this call may use, from both sides' HELLOs. Until a HELLO arrives, and for any
     * v1 buddy, this is the original call: ADPCM, no position, no auth, no group.
     */
    @Volatile var features: NegotiatedFeatures = NegotiatedFeatures.LEGACY
        private set
    private var link: PeerLink? = null
    private var selector: LinkSelector? = null
    private var arbiter: FloorArbiter? = null
    private var ticker: Job? = null

    private val sessionId = Hello.newSessionId()
    private val audioSeq = AtomicInteger(0)
    private val controlSeq = AtomicInteger(0)

    /** 100 ms of audio kept back so the detector's attack delay does not clip a word. */
    private val preroll = ArrayDeque<ShortArray>(AudioConstants.PREROLL_FRAMES)

    private var startedAtMs = 0L

    /** Set while the system owns the headset, e.g. a real phone call. */
    @Volatile private var suspendedForInterruption = false

    val focus = FocusManager(
        audioManager = audioManager,
        onFocusLost = { permanent -> onInterruption(permanent) },
        onFocusRegained = { onInterruptionOver() },
    )

    // ------------------------------------------------------------------- start

    fun start(role: LinkRole, device: BluetoothDevice?) {
        if (_status.value.phase != CallPhase.Idle && _status.value.phase != CallPhase.Ended &&
            _status.value.phase != CallPhase.Error
        ) return

        if (!Permissions.hasMicrophone(context)) {
            fail("Microphone permission is not granted")
            return
        }
        if (!Permissions.hasBluetooth(context)) {
            fail("Bluetooth permission is not granted")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is off")
            return
        }

        val settings = prefs.current
        terminal = false
        rideCode = RideCode.parseOrNull(settings.rideCode)
        allowLegacy = settings.allowLegacyBuddy
        if (rideCode == null && !allowLegacy) {
            fail("Set a ride code first")
            return
        }
        if (throttle.isLocked) {
            fail("Too many wrong ride codes. Try again in ${secondsLeft()} s")
            return
        }
        focus.takeFocus = settings.takeAudioFocus
        vad.sensitivity = settings.vadSensitivity
        applyVoiceLevel(settings.voiceLevelDb, settings.takeAudioFocus)

        startedAtMs = System.currentTimeMillis()
        update {
            it.copy(
                phase = CallPhase.Starting,
                isHost = role == LinkRole.Host,
                statusText = if (role == LinkRole.Host) "Waiting for buddy" else "Connecting",
                error = null,
            )
        }

        val hello = Hello(
            version = Protocol.VERSION,
            sampleRate = AudioConstants.SAMPLE_RATE,
            frameSamples = AudioConstants.FRAME_SAMPLES,
            codec = Hello.CODEC_ADPCM,
            sessionId = sessionId,
            riderName = settings.riderName,
            capabilities = localCaps(),
        )

        val arb = FloorArbiter(sessionId, floorActions)
        arbiter = arb

        // The selector is itself a PeerLink: it owns the radios and swaps them without
        // the audio pipeline ever learning which one is underneath.
        val l = LinkSelector(
            context = context,
            adapter = adapter,
            role = role,
            peerDevice = device,
            localHello = hello,
            preference = settings.transport,
        )
        selector = l
        link = l
        arb.rttMillisProvider = { l.rttMillis.value }
        l.setOnMessage(::onMessage)
        l.start()

        player.preferOboe = !settings.legacyAudioPath
        player.onStatus = ::onAudioStatus
        jitter.decoder = codec
        if (!player.start()) {
            fail("Could not open the audio output")
            return
        }

        val m = MicCapture(
            audioManager = audioManager,
            source = settings.micSource,
            noiseSuppression = settings.noiseSuppression,
            useOboe = !settings.legacyAudioPath,
            onStatus = ::onAudioStatus,
            onFrame = ::onMicFrame,
        )
        if (!m.start()) {
            fail("Could not open the microphone")
            return
        }
        mic = m

        observeLink(l)
        startTicker(l, arb)
    }

    /** Switches to the codec both phones agreed on. Idempotent, and a no-op for ADPCM. */
    private fun selectCodec(f: NegotiatedFeatures) {
        val wanted = CodecChoice.choose(f.codec, opusAvailable)
        val current = codec
        if (wanted == current.id) return

        val next: AudioCodec = if (wanted == CodecId.Opus) {
            val engine = NativeOpusEngine(bitratePolicy.bitrate)
            if (engine.usable) OpusCodec(engine, bitratePolicy.bitrate)
            else { engine.release(); AdpcmAudioCodec() }
        } else {
            AdpcmAudioCodec()
        }
        // Decoder state does not carry across codecs, so start the jitter buffer clean.
        jitter.decoder = next
        jitter.reset()
        codec = next
        current.release()
    }

    /** Mic or speaker stream being recovered after a route change; shown to the rider. */
    private fun onAudioStatus(message: String) {
        if (message.isNotEmpty()) update { it.copy(statusText = message) }
        else link?.status?.value?.let { s -> update { it.copy(statusText = s) } }
    }

    private fun observeLink(l: PeerLink) {
        scope.launch {
            l.state.collect { st ->
                val phase = when (st) {
                    LinkState.Idle -> CallPhase.Starting
                    LinkState.Listening, LinkState.Connecting, LinkState.Handshaking -> CallPhase.Connecting
                    LinkState.Connected -> CallPhase.Active
                    LinkState.Reconnecting -> CallPhase.Reconnecting
                    LinkState.Failed -> CallPhase.Error
                    LinkState.Closed -> CallPhase.Ended
                }
                if (st == LinkState.Connected) {
                    // A fresh socket must not replay the previous one's audio.
                    jitter.reset()
                    arbiter?.reset()
                    vad.reset()
                    preroll.clear()
                    // Every new socket is a new connection: new nonces, new keys.
                    synchronized(securityLock) { connectionEpoch++ }
                    evaluateSecurity()
                } else {
                    dropSecurity()
                    if (st == LinkState.Reconnecting) {
                        jitter.reset()
                        arbiter?.onLinkLost()
                        focus.undock()
                    }
                }
                // After a security failure the link is closed on purpose; keep saying why.
                update { it.copy(phase = if (terminal) CallPhase.Error else phase, link = st) }
            }
        }
        scope.launch { l.status.collect { s -> if (!terminal) update { it.copy(statusText = s) } } }
        scope.launch { l.rttMillis.collect { r -> update { it.copy(rttMs = r) } } }
        scope.launch {
            l.peerHello.collect { h ->
                update { it.copy(peerName = h?.riderName) }
                if (h != null) {
                    val f = NegotiatedFeatures.negotiate(localCaps(), h)
                    features = f
                    selectCodec(f)
                    Log.i(TAG, "buddy v${h.version}, features: $f, codec=${codec.id}")
                    evaluateSecurity()
                }
            }
        }
    }

    private fun startTicker(l: PeerLink, arb: FloorArbiter) {
        ticker?.cancel()
        var ticks = 0L
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                arb.tick()
                val micRms = mic?.lastRms ?: 0.0
                // The switch rules run on this number, so it has to be current.
                selector?.lossPercent = jitter.lossPercent()
                _micLevel.value = (micRms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                ticks++

                // One greppable line every 5 s. On the road nobody is watching the
                // screen, so this is the only record of how the link actually behaved.
                if (ticks % STATS_EVERY_TICKS == 0L) {
                    Log.i(
                        STATS_TAG,
                        "link=${l.state.value} floor=${arb.state.value} " +
                            "rtt=${l.rttMillis.value}ms " +
                            "loss=%.1f%% ".format(jitter.lossPercent()) +
                            "jitter=${jitter.depth}/${jitter.targetDepth}f " +
                            "played=${jitter.playedFrames} lost=${jitter.lostFrames} " +
                            "late=${jitter.lateFrames} underruns=${jitter.underruns} " +
                            "micRms=${micRms.toInt()} vadMargin=%.1fdB ".format(vad.lastMarginDb) +
                            "trackUnderruns=${player.trackUnderruns} " +
                            "codec=${codec.id}@${codec.bitrate} fecPlc=${jitter.codecConcealed} " +
                            "undecodable=${jitter.undecodable} codecMismatch=$codecMismatches " +
                            "io=${mic?.path}/${player.path} " +
                            "dropSend=${selector?.droppedSends ?: 0} " +
                            "ns=${mic?.noiseSuppression} hwfx=${mic?.platformEffectsReport} " +
                            "focus=${focus.isHeld} ptt=${arb.pushToTalk} " +
                            "sec=$security rejected=${cipher?.rejected ?: 0} " +
                            "cleartextDropped=$cleartextDropped " +
                            "t=${(System.currentTimeMillis() - startedAtMs) / 1000}s",
                    )
                }
                if (ticks % STATUS_EVERY_TICKS == 0L) {
                    adaptBitrate(l)
                    update {
                        it.copy(
                            floor = arb.state.value,
                            muted = arb.state.value == FloorState.Muted,
                            pushToTalk = arb.pushToTalk,
                            lossPercent = jitter.lossPercent(),
                            jitterDepth = jitter.depth,
                            focusHeld = focus.isHeld,
                            elapsedSeconds = if (startedAtMs == 0L) 0
                            else (System.currentTimeMillis() - startedAtMs) / 1000,
                        )
                    }
                }
            }
        }
    }

    /**
     * Once a second: feed the link's recent loss and RTT to the bitrate policy and apply
     * what it says. Loss is measured over the last second, not since the call began, so
     * the rate recovers when the link does.
     */
    private fun adaptBitrate(l: PeerLink) {
        val lost = jitter.lostFrames
        val played = jitter.playedFrames
        val dLost = lost - prevLost
        val dPlayed = played - prevPlayed
        prevLost = lost
        prevPlayed = played
        val total = dLost + dPlayed
        val windowLoss = if (total <= 0) 0.0 else dLost * 100.0 / total
        codec.setBitrate(bitratePolicy.onSample(windowLoss, l.rttMillis.value))
    }

    // --------------------------------------------------------------- send path

    /** Called on the capture thread, once per 20 ms. */
    private fun onMicFrame(frame: ShortArray) {
        if (suspendedForInterruption) return
        val arb = arbiter ?: return
        val l = link ?: return

        // Capture keeps running in all three floor states so the detector can fire;
        // only Talking feeds the encoder.
        val event = vad.process(frame)

        // Until a peer is actually on the other end, run capture and the detector - the
        // level meter and the noise floor both need them - but take no floor, duck
        // nothing and queue nothing. Encoding into a socket that does not exist yet only
        // burns CPU and fills the outbox: while merely waiting to be joined this pushed
        // dropSend past 1700 frames in 45 seconds and ducked the rider's music the whole
        // time.
        if (l.state.value != LinkState.Connected || !mediaAllowed()) {
            rememberForPreroll(frame)
            return
        }

        // In push-to-talk the detector still runs - it drives the level meter and keeps
        // the noise floor current - but only the button takes the floor.
        if (prefs.current.talkMode == TalkMode.Open) {
            when (event) {
                VadEvent.Open -> {
                    val startMicros = System.nanoTime() / 1000L -
                        (AudioConstants.FRAME_MS * 1000L * vad.sensitivity.attackFrames)
                    if (arb.onVadOpen(startMicros)) {
                        focus.duck()
                        flushPreroll(l)
                    }
                }
                VadEvent.Close -> arb.onVadClose()
                VadEvent.None -> Unit
            }
        }

        if (arb.state.value == FloorState.Talking) {
            sendAudio(l, frame)
        } else {
            rememberForPreroll(frame)
        }
    }

    private fun rememberForPreroll(frame: ShortArray) {
        if (preroll.size >= AudioConstants.PREROLL_FRAMES) preroll.removeFirst()
        preroll.addLast(frame.copyOf())
    }

    /** Sends the held-back frames so the first syllable is not lost to the attack delay. */
    private fun flushPreroll(l: PeerLink) {
        while (preroll.isNotEmpty()) {
            sendAudio(l, preroll.removeFirst())
        }
    }

    private fun sendAudio(l: PeerLink, frame: ShortArray) {
        val c = codec
        val n = c.encode(frame, txBuf)
        if (n <= 0) return
        val seq = audioSeq.getAndIncrement() and 0xFFFF
        sendFrame(l, Protocol.TYPE_AUDIO, seq, c.wireFlag, txBuf.copyOf(n))
    }

    // ------------------------------------------------------------ receive path

    /** Called on the link's reader thread. */
    private fun onMessage(raw: Message) {
        val arb = arbiter ?: return

        // The security handshake and END are always cleartext. Everything else goes through
        // the gate: opened while encrypted, refused while the check is still pending.
        if (raw.type == Protocol.TYPE_AUTH_CHALLENGE || raw.type == Protocol.TYPE_AUTH_RESPONSE) {
            authInbox?.trySend(raw)
            return
        }
        val m = if (raw.type == Protocol.TYPE_END) raw else (admit(raw) ?: return)

        when (m.type) {
            Protocol.TYPE_AUDIO -> {
                // Any frame from the holder refreshes the watchdog that would otherwise
                // free the floor after a link drop mid-sentence.
                arb.onRemoteAudio()
                // The flags byte names the codec. A frame from a codec we are not running
                // (a mid-call mismatch, or a buggy peer) is dropped rather than decoded.
                if (m.flags != codec.wireFlag) {
                    codecMismatches++
                    return
                }
                // Decoding happens at playout, so the codec can conceal a missing frame.
                jitter.putPayload(m.seq, m.payload)
                focus.duck()
            }
            Protocol.TYPE_FLOOR_TAKEN -> {
                val remoteTs = Codec.readLong(m.payload, 0)
                val remoteSession = if (m.payload.size >= 16) Codec.readLong(m.payload, 8) else 0L
                val offset = link?.clockOffsetMicros ?: 0L
                // Convert the buddy's stamp into this phone's clock before comparing.
                arb.onRemoteFloorTaken(remoteTs - offset, remoteSession, _status.value.peerName)
                focus.duck()
            }
            Protocol.TYPE_FLOOR_RELEASE -> {
                arb.onRemoteFloorRelease()
                focus.undock()
            }
            Protocol.TYPE_FLOOR_DENIED -> {
                arb.onRemoteFloorDenied(_status.value.peerName)
            }
            Protocol.TYPE_END -> {
                update { it.copy(statusText = "Buddy ended the call") }
                stop()
            }
            else -> Log.d(TAG, "ignoring ${Protocol.typeName(m.type)}")
        }
    }

    /**
     * Runs a frame through [FrameGate]. Returns the message to act on, with the payload
     * decrypted and the encryption bit cleared, or null if it must be dropped.
     */
    private fun admit(m: Message): Message? =
        when (val r = FrameGate.admit(security, cipher, m)) {
            is FrameGate.Result.Accept -> r.message
            FrameGate.Result.WrongForm -> { cleartextDropped++; null }
            FrameGate.Result.Invalid, FrameGate.Result.NotReady -> null
        }

    // ------------------------------------------------------------ floor wiring

    private val floorActions = object : FloorActions {
        override fun sendFloorTaken(startedAtMicros: Long) {
            val body = ByteArray(16)
            putLong(body, 0, startedAtMicros)
            putLong(body, 8, sessionId)
            sendControl(Protocol.TYPE_FLOOR_TAKEN, body)
        }

        override fun sendFloorRelease() {
            sendControl(Protocol.TYPE_FLOOR_RELEASE, null)
            focus.undock()
        }

        override fun sendFloorDenied() {
            sendControl(Protocol.TYPE_FLOOR_DENIED, null)
        }

        override fun beep() {
            if (prefs.current.beepWhenMuted) beeper.request()
        }

        override fun onStateChanged(state: FloorState) {
            update { it.copy(floor = state, muted = state == FloorState.Muted) }
            if (state == FloorState.Idle) focus.undock() else focus.duck()
        }
    }

    // ---------------------------------------------------------------- security
    //
    // Authentication and encryption live here, in the controller, and not in the links: the
    // same code protects Bluetooth, Wi-Fi Direct and whatever comes next, and the audio
    // pipeline still never learns which radio is underneath.
    //
    // Every new connection (a first connect, a reconnect, a transport switch) runs a fresh
    // challenge-response, derives fresh keys, and starts its frame counters at zero. Until
    // that finishes no audio or floor traffic is sent or accepted.

    private val throttle = AuthThrottle()
    private val seenNonces = NonceCache()
    private val securityLock = Any()

    /** This call's ride code, or null when none is set (possible only with legacy allowed). */
    @Volatile private var rideCode: RideCode? = null
    @Volatile private var allowLegacy = false

    @Volatile private var security = ChannelSecurity.Pending
    @Volatile private var cipher: FrameCipher? = null
    @Volatile private var authInbox: Channel<Message>? = null
    private var authSession: AuthSession? = null
    private var authJobs: List<Job> = emptyList()

    private var connectionEpoch = 0
    private var handledEpoch = -1
    private var handledAuth: Boolean? = null

    /** Set when the call is being ended for a security reason, so the UI keeps saying why. */
    @Volatile private var terminal = false
    @Volatile private var cleartextDropped = 0L

    /** AUTH is advertised only when there is a ride code to prove anything with. */
    private fun localCaps(): Int = Capabilities.local(opusAvailable, rideCode != null)

    private fun secondsLeft(): Long = (throttle.remainingMs() + 999) / 1000

    /** True when audio and floor traffic may flow, encrypted or (by choice) not. */
    private fun mediaAllowed(): Boolean =
        security == ChannelSecurity.Encrypted || security == ChannelSecurity.Unencrypted

    /**
     * Decides, once per connection, how this call is protected. Called whenever either input
     * changes - the link reaching Connected, or the buddy's HELLO arriving - and idempotent.
     */
    private fun evaluateSecurity() {
        val l = link ?: return
        if (l.state.value != LinkState.Connected) return
        val hello = l.peerHello.value ?: return
        synchronized(securityLock) {
            val f = NegotiatedFeatures.negotiate(localCaps(), hello)
            if (handledEpoch == connectionEpoch && handledAuth == f.auth) return
            handledEpoch = connectionEpoch
            handledAuth = f.auth
            resetSecurityLocked()

            val code = rideCode
            when (SecurityPolicy.decide(code != null, f.auth, allowLegacy)) {
                SecurityPolicy.Decision.Authenticate -> beginAuth(l, code!!)
                SecurityPolicy.Decision.ProceedUnencrypted -> {
                    // The rider allowed it; the call screen shows a warning the whole time.
                    Log.w(TAG, "buddy v${hello.version} cannot encrypt; continuing unprotected by choice")
                    security = ChannelSecurity.Unencrypted
                    update { it.copy(security = ChannelSecurity.Unencrypted) }
                }
                SecurityPolicy.Decision.RefuseBuddy -> failSecurity(
                    "Your buddy's app can't encrypt calls. Update it, or allow an " +
                        "unencrypted buddy in Settings."
                )
            }
        }
    }

    private fun beginAuth(l: PeerLink, code: RideCode) {
        if (throttle.isLocked) {
            failSecurity("Too many wrong ride codes. Try again in ${secondsLeft()} s")
            return
        }
        val session = AuthSession(code, sessionId, seenNonces)
        val inbox = Channel<Message>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        authSession = session
        authInbox = inbox
        security = ChannelSecurity.Authenticating
        update { it.copy(security = ChannelSecurity.Authenticating, statusText = "Securing the call") }

        // Re-send our challenge until the buddy answers: it may not have been listening for
        // the first one. Gives up after a while rather than hanging forever.
        val challenge = AuthChallenge(session.localNonce, sessionId).toBytes()
        val sender = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive && session.state == AuthSession.State.AwaitingPeer) {
                sendRaw(l, Protocol.TYPE_AUTH_CHALLENGE, challenge)
                delay(AUTH_RESEND_MS)
                if (session.state == AuthSession.State.AwaitingPeer &&
                    System.currentTimeMillis() - startedAt > AUTH_TIMEOUT_MS
                ) {
                    failSecurity("The security check with your buddy timed out")
                    return@launch
                }
            }
        }
        // One consumer, so challenge and response are handled in the order they arrived.
        // The key derivation inside is slow on purpose and must not run on the reader thread.
        val worker = scope.launch {
            for (m in inbox) handleAuthMessage(l, session, m)
        }
        authJobs = listOf(sender, worker)
    }

    private fun handleAuthMessage(l: PeerLink, session: AuthSession, m: Message) {
        when (m.type) {
            Protocol.TYPE_AUTH_CHALLENGE -> {
                val ch = AuthChallenge.parse(m.payload) ?: return
                val peerSid = if (ch.sessionId != 0L) ch.sessionId
                else l.peerHello.value?.sessionId ?: 0L
                session.onPeerChallenge(ch.nonce, peerSid).forEach { applyAuth(l, session, it) }
            }
            Protocol.TYPE_AUTH_RESPONSE -> {
                val r = AuthResponse.parse(m.payload) ?: return
                applyAuth(l, session, session.onPeerResponse(r.mac))
            }
        }
    }

    private fun applyAuth(l: PeerLink, session: AuthSession, outcome: AuthSession.Outcome) {
        when (outcome) {
            is AuthSession.Outcome.SendResponse ->
                sendRaw(l, Protocol.TYPE_AUTH_RESPONSE, AuthResponse(outcome.mac).toBytes())

            AuthSession.Outcome.Authenticated -> synchronized(securityLock) {
                if (authSession !== session) return  // a newer connection took over
                cipher = session.newCipher()
                security = ChannelSecurity.Encrypted
                throttle.recordSuccess()
                val text = link?.status?.value ?: "Connected"
                update { it.copy(security = ChannelSecurity.Encrypted, statusText = text) }
                Log.i(TAG, "channel authenticated and encrypted")
            }

            AuthSession.Outcome.WrongCode -> {
                throttle.recordFailure()
                failSecurity("Wrong ride code. Check it matches your buddy's.")
            }

            is AuthSession.Outcome.Rejected -> failSecurity("Security check failed (${outcome.reason})")
            AuthSession.Outcome.None -> Unit
        }
    }

    /** Ends the call for a security reason: say why, close the link, keep the reason on screen. */
    private fun failSecurity(message: String) {
        terminal = true
        synchronized(securityLock) { resetSecurityLocked() }
        ticker?.cancel()
        ticker = null
        update { it.copy(security = ChannelSecurity.Pending) }
        fail(message)
    }

    /** The link is gone: forget keys, they are good for one connection only. */
    private fun dropSecurity() {
        synchronized(securityLock) {
            if (security != ChannelSecurity.Pending || authSession != null) resetSecurityLocked()
            handledAuth = null
        }
        if (_status.value.security != ChannelSecurity.Pending) {
            update { it.copy(security = ChannelSecurity.Pending) }
        }
    }

    private fun resetSecurityLocked() {
        authJobs.forEach { it.cancel() }
        authJobs = emptyList()
        authInbox?.close()
        authInbox = null
        authSession?.close()
        authSession = null
        cipher = null
        security = ChannelSecurity.Pending
    }

    /**
     * Sends a control or audio frame through the security layer. Encrypted when the
     * channel is; sent as-is only when the rider allowed an unencrypted buddy; dropped
     * while the check is still running.
     */
    private fun sendFrame(l: PeerLink, type: Byte, seq: Int, flags: Byte, payload: ByteArray?): Boolean =
        when (security) {
            ChannelSecurity.Encrypted -> {
                val c = cipher
                if (c == null) false else try {
                    val sealed = c.seal(type, seq, flags, payload ?: ByteArray(0))
                    l.send(Codec.encode(type, seq, sealed.flags, sealed.payload))
                } catch (e: IllegalStateException) {
                    // 2^32 frames sent on one key: never reuse a nonce, rekey by reconnecting.
                    failSecurity("The secure session ran out of keys. Please reconnect.")
                    false
                }
            }
            ChannelSecurity.Unencrypted -> l.send(Codec.encode(type, seq, flags, payload))
            ChannelSecurity.Pending, ChannelSecurity.Authenticating -> false
        }

    private fun sendControl(type: Byte, body: ByteArray?) {
        val l = link ?: return
        sendFrame(l, type, controlSeq.getAndIncrement() and 0xFFFF, 0, body)
    }

    /** Handshake traffic: cleartext by design, sent regardless of the channel state. */
    private fun sendRaw(l: PeerLink, type: Byte, body: ByteArray) {
        l.send(Codec.encode(type, controlSeq.getAndIncrement() and 0xFFFF, 0, body))
    }

    // ------------------------------------------------------------ interruption

    /**
     * A real phone call takes the headset to HFP and the system takes focus. Capture
     * pauses, the link stays alive on its keep-alive pings, and the output is re-opened
     * afterwards because the A2DP route has been torn down and rebuilt underneath.
     */
    private fun onInterruption(permanent: Boolean) {
        suspendedForInterruption = true
        update { it.copy(statusText = if (permanent) "Audio taken by another app" else "Paused for a call") }
    }

    private fun onInterruptionOver() {
        suspendedForInterruption = false
        jitter.reset()
        vad.reset()
        preroll.clear()
        player.restart()
        update { it.copy(statusText = "Resumed") }
    }

    // ----------------------------------------------------------------- control

    fun setPushToTalk(down: Boolean) {
        arbiter?.setPushToTalk(down, _status.value.peerName)
        if (down) focus.duck() else focus.undock()
        update { it.copy(pushToTalk = down) }
    }

    fun togglePushToTalk() = setPushToTalk(!(arbiter?.pushToTalk ?: false))

    /** The radio currently carrying the call, for the status line. */
    val activeTransport: Transport? get() = selector?.activeTransport?.value

    /** True while the selector is moving the session between radios. */
    val isSwitchingTransport: Boolean get() = selector?.switching?.value == true

    /** The rider asking for the other radio by hand. */
    fun switchTransport(target: Transport) { selector?.switchNow(target) }

    /**
     * Steps Off -> Light -> Medium -> Strong -> Off and applies it to the live capture path, so the
     * rider can judge the setting against the actual wind instead of in a quiet car park.
     */
    fun cycleNoiseSuppression(): NoiseSuppression {
        val next = when (prefs.current.noiseSuppression) {
            NoiseSuppression.Off -> NoiseSuppression.Light
            NoiseSuppression.Light -> NoiseSuppression.Medium
            NoiseSuppression.Medium -> NoiseSuppression.Strong
            NoiseSuppression.Strong -> NoiseSuppression.Off
        }
        prefs.update { it.copy(noiseSuppression = next) }
        mic?.noiseSuppression = next
        return next
    }

    fun applySettingsChange() {
        val s = prefs.current
        vad.sensitivity = s.vadSensitivity
        // Suppression strength changes take effect on the next frame; no call restart.
        mic?.noiseSuppression = s.noiseSuppression
        focus.takeFocus = s.takeAudioFocus
        applyVoiceLevel(s.voiceLevelDb, s.takeAudioFocus)
    }

    private fun applyVoiceLevel(voiceLevelDb: Int, takeFocus: Boolean) {
        // With no focus taken the music is not ducked, so the voice is lifted instead.
        val boost = if (takeFocus) 0.0 else FocusManager.NO_FOCUS_VOICE_BOOST_DB
        player.setGainDb(voiceLevelDb.toDouble() + boost)
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        mic?.stop()
        mic = null
        link?.close()
        link = null
        selector = null
        player.stop()
        focus.release()
        jitter.reset()
        arbiter?.reset()
        arbiter = null
        preroll.clear()
        // Hand back to ADPCM so the next call starts from the original, and free the
        // native Opus state now that nothing is using it.
        jitter.decoder = null
        val finished = codec
        codec = AdpcmAudioCodec()
        finished.release()
        features = NegotiatedFeatures.LEGACY
        synchronized(securityLock) {
            resetSecurityLocked()
            connectionEpoch = 0
            handledEpoch = -1
            handledAuth = null
        }
        terminal = false
        rideCode = null
        bitratePolicy.reset()
        prevLost = 0
        prevPlayed = 0
        update {
            it.copy(
                phase = CallPhase.Ended,
                link = LinkState.Closed,
                floor = FloorState.Idle,
                statusText = "Call ended",
                rttMs = -1,
                security = ChannelSecurity.Pending,
            )
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        update { it.copy(phase = CallPhase.Error, error = message, statusText = message) }
        mic?.stop(); mic = null
        link?.close(); link = null
        player.stop()
        focus.release()
    }

    private inline fun update(block: (CallStatus) -> CallStatus) {
        _status.value = block(_status.value)
    }

    private fun putLong(dst: ByteArray, off: Int, v: Long) {
        ByteBuffer.wrap(dst, off, 8).order(ByteOrder.BIG_ENDIAN).putLong(v)
    }
}
