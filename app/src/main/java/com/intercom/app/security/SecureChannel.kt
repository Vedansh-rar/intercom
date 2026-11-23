package com.intercom.app.security

import com.intercom.app.protocol.AuthChallenge
import com.intercom.app.protocol.AuthResponse
import com.intercom.app.protocol.Codec
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.Protocol
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Phase 3 handshake and frame encryption for ONE connection, packaged so a group ride
 * can run one per Nearby endpoint.
 *
 * It does what [com.intercom.app.call.CallController] does for a two-rider call, minus the
 * call: send our challenge (repeating until answered), feed the peer's challenge and
 * response to an [AuthSession] on a worker, and once authenticated seal and open frames
 * with the derived key. It is deliberately a *copy* of that logic's shape, not a rewrite of
 * it - the two-rider path is shipped and tested, and Phase 4 must not disturb it. Moving the
 * controller onto this class is a worthwhile follow-up.
 *
 * Until [state] is [ChannelSecurity.Encrypted] nothing but the handshake passes:
 * [receive] returns null for everything else and [seal] returns null.
 *
 * Threads: [receive] must be called from one thread at a time (the group's receive
 * executor), which is what lets [FrameCipher]'s replay window stay lock-free. [seal] may
 * be called from any thread. The slow key derivation runs on [worker], never on the
 * caller's thread.
 *
 * @param send puts a framed message on the wire for this connection; false if it cannot
 * @param onResult called once with the outcome, from [worker] or [timer]
 */
class SecureChannel(
    private val code: RideCode,
    private val sessionId: Long,
    private val seenNonces: NonceCache,
    private val send: (ByteArray) -> Boolean,
    private val worker: Executor,
    private val timer: ScheduledExecutorService,
    private val onResult: (Result) -> Unit,
    private val iterations: Int = KeyDerivation.DEFAULT_ITERATIONS,
    private val resendMs: Long = RESEND_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    sealed class Result {
        object Authenticated : Result()
        object WrongCode : Result()
        class Failed(val reason: String) : Result()
    }

    private val session = AuthSession(code, sessionId, seenNonces, iterations)
    private val ctr = AtomicInteger()
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile var state: ChannelSecurity = ChannelSecurity.Pending
        private set
    @Volatile private var cipher: FrameCipher? = null
    private var resend: ScheduledFuture<*>? = null
    @Volatile private var started = false
    @Volatile private var closed = false

    /**
     * [AuthSession] is not thread-safe, and the worker is a pool: a challenge and the
     * response behind it can be picked up by two threads at once. Handshake work for this
     * channel therefore runs one task at a time. (A response that overtakes its challenge
     * is handled by AuthSession itself.)
     */
    private val authLock = Any()

    /** Begins the handshake: sends our challenge now and every [resendMs] until done. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        state = ChannelSecurity.Authenticating
        val challenge = AuthChallenge(session.localNonce, sessionId).toBytes()
        val began = System.currentTimeMillis()
        resend = timer.scheduleWithFixedDelay({
            if (session.state != AuthSession.State.AwaitingPeer) {
                resend?.cancel(false)
            } else if (System.currentTimeMillis() - began > timeoutMs) {
                fail(Result.Failed("timed out"))
            } else {
                sendRaw(Protocol.TYPE_AUTH_CHALLENGE, challenge)
            }
        }, 0, resendMs, TimeUnit.MILLISECONDS)
    }

    /**
     * One frame from the peer. Handshake frames are queued for the worker and null is
     * returned; once encrypted, anything else is opened and returned as plaintext. A frame
     * that fails to open, or arrives before the channel is ready, yields null.
     */
    fun receive(m: Message): Message? {
        if (m.type == Protocol.TYPE_AUTH_CHALLENGE || m.type == Protocol.TYPE_AUTH_RESPONSE) {
            // A challenge that arrives again after we are done means the peer never saw our
            // response: answer it again, from the same state, so it can finish too.
            val answerable = state == ChannelSecurity.Authenticating ||
                (state == ChannelSecurity.Encrypted && m.type == Protocol.TYPE_AUTH_CHALLENGE)
            if (answerable) worker.execute { handleAuth(m) }
            return null
        }
        if (state != ChannelSecurity.Encrypted) return null
        return when (val r = FrameGate.admit(ChannelSecurity.Encrypted, cipher, m)) {
            is FrameGate.Result.Accept -> r.message
            else -> null
        }
    }

    /** Frames [payload] for this peer, encrypted, or null if the channel is not ready. */
    fun seal(type: Byte, flags: Byte, payload: ByteArray): ByteArray? {
        val c = cipher ?: return null
        if (state != ChannelSecurity.Encrypted) return null
        val seq = ctr.getAndIncrement() and 0xFFFF
        return try {
            val s = c.seal(type, seq, flags, payload)
            Codec.encode(type, seq, s.flags, s.payload)
        } catch (e: IllegalStateException) {
            // Counter space used up on this key: refuse, the link will be rebuilt.
            fail(Result.Failed("key exhausted"))
            null
        }
    }

    fun close() {
        closed = true
        resend?.cancel(false)
        state = ChannelSecurity.Pending
        cipher = null
        session.close()
    }

    private fun handleAuth(m: Message) {
        synchronized(authLock) { handleAuthLocked(m) }
    }

    private fun handleAuthLocked(m: Message) {
        if (closed) return
        try {
            when (m.type) {
                Protocol.TYPE_AUTH_CHALLENGE -> {
                    val ch = AuthChallenge.parse(m.payload) ?: return
                    // Group peers always send a session ID; zero means a v1-shaped challenge.
                    if (ch.sessionId == 0L) {
                        fail(Result.Failed("peer is too old for group rides"))
                        return
                    }
                    session.onPeerChallenge(ch.nonce, ch.sessionId).forEach(::apply)
                }
                Protocol.TYPE_AUTH_RESPONSE -> {
                    val r = AuthResponse.parse(m.payload) ?: return
                    apply(session.onPeerResponse(r.mac))
                }
            }
        } catch (t: Throwable) {
            fail(Result.Failed("handshake error"))
        }
    }

    private fun apply(o: AuthSession.Outcome) {
        if (closed) return
        when (o) {
            is AuthSession.Outcome.SendResponse ->
                sendRaw(Protocol.TYPE_AUTH_RESPONSE, AuthResponse(o.mac).toBytes())
            AuthSession.Outcome.Authenticated -> {
                cipher = session.newCipher()
                state = ChannelSecurity.Encrypted
                resend?.cancel(false)
                report(Result.Authenticated)
            }
            AuthSession.Outcome.WrongCode -> fail(Result.WrongCode)
            is AuthSession.Outcome.Rejected -> fail(Result.Failed(o.reason))
            AuthSession.Outcome.None -> Unit
        }
    }

    private fun fail(r: Result) {
        resend?.cancel(false)
        state = ChannelSecurity.Pending
        cipher = null
        report(r)
    }

    private fun report(r: Result) {
        if (reported.compareAndSet(false, true)) onResult(r)
    }

    private fun sendRaw(type: Byte, body: ByteArray) {
        send(Codec.encode(type, ctr.getAndIncrement() and 0xFFFF, 0, body))
    }

    companion object {
        const val RESEND_MS = 700L
        const val TIMEOUT_MS = 15_000L
    }
}
