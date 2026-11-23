package com.intercom.app.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * One run of the mutual challenge-response, from this phone's side. Pure logic: it never
 * touches a socket, so every branch is unit-tested. [CallController] feeds it messages and
 * sends what it returns.
 *
 * ## Protocol
 *
 * After HELLO each side sends `AUTH_CHALLENGE(nonce)`, a fresh 16-byte random value. When
 * it has the other side's nonce, each side
 *
 *  1. derives the keys with [KeyDerivation.derive] (PBKDF2 over the code, salted with both
 *     nonces and both session IDs),
 *  2. answers with `AUTH_RESPONSE(HMAC-SHA256(authKey, tag | myNonce | theirNonce |
 *     mySessionId | theirSessionId))`. Each side's session ID travels in its challenge.
 *
 * Each side recomputes the other's expected response and compares in constant time. A match
 * proves the peer knows the code and is answering *this* handshake: the MAC covers my fresh
 * nonce, so a recorded response from an earlier connection fails. Responses are built from
 * the sender's own point of view (own nonce first), so a response cannot be reflected back
 * at its sender, and equal nonces are refused outright.
 *
 * The code itself, and any hash of it, is never transmitted.
 *
 * Not thread-safe: the caller serialises calls (the controller uses one worker).
 */
class AuthSession(
    private val code: RideCode,
    val localSessionId: Long,
    private val seenNonces: NonceCache = NonceCache(),
    private val iterations: Int = KeyDerivation.DEFAULT_ITERATIONS,
    random: SecureRandom = SecureRandom(),
) {
    enum class State { AwaitingPeer, Authenticated, Failed }

    /** What the caller should do after feeding in a message. */
    sealed class Outcome {
        /** Send this as AUTH_RESPONSE. */
        class SendResponse(val mac: ByteArray) : Outcome()

        /** Nothing to do yet, or the message was a harmless duplicate. */
        object None : Outcome()

        /** The peer proved it knows the code; the keys are ready. */
        object Authenticated : Outcome()

        /** The peer's response did not match: the codes differ (or someone is guessing). */
        object WrongCode : Outcome()

        /** The handshake itself is broken (reflected or reused nonce). Close the link. */
        class Rejected(val reason: String) : Outcome()
    }

    /** Our challenge; send it as AUTH_CHALLENGE as soon as the peer's HELLO is in. */
    val localNonce: ByteArray = ByteArray(KeyDerivation.NONCE_BYTES).also(random::nextBytes)

    @Volatile var state: State = State.AwaitingPeer
        private set

    /** The peer's session ID, as it announced it in its challenge. */
    private var peerSessionId: Long = 0L

    private var peerNonce: ByteArray? = null
    private var keys: KeyDerivation.SessionKeys? = null
    private var ourResponse: ByteArray? = null
    private var pendingPeerResponse: ByteArray? = null

    /**
     * The keys and direction for [FrameCipher], once the peer is authenticated. The side
     * with the lexicographically lower nonce uses direction 0.
     */
    fun newCipher(): FrameCipher? {
        val k = keys ?: return null
        val n = peerNonce ?: return null
        if (state != State.Authenticated) return null
        val dir = if (KeyDerivation.compareUnsigned(localNonce, n) < 0) 0 else 1
        return FrameCipher(k.enc, dir)
    }

    /** Wipes key material; call when the connection is gone. */
    fun close() {
        keys?.wipe()
        keys = null
        ourResponse = null
        pendingPeerResponse = null
    }

    /**
     * The peer's challenge arrived. Does the expensive key derivation, so run it off the
     * reader thread. Returns what to do, in order: our response always goes first, so the
     * peer can reach its own verdict even if ours is a failure and we then close.
     */
    fun onPeerChallenge(nonce: ByteArray, peerSessionId: Long): List<Outcome> {
        if (state == State.Failed) return emptyList()
        if (nonce.size != KeyDerivation.NONCE_BYTES) return emptyList()

        val known = peerNonce
        if (known != null) {
            // The peer re-sent its challenge because it did not see our response. Answer
            // again from the same state. A *different* nonce mid-handshake is ignored: only
            // a new connection starts a new session.
            val resp = ourResponse
            return if (known.contentEquals(nonce) && resp != null) listOf(Outcome.SendResponse(resp))
            else emptyList()
        }

        if (nonce.contentEquals(localNonce)) {
            return listOf(fail("peer nonce equals ours (reflected challenge)"))
        }
        if (!seenNonces.add(nonce)) {
            return listOf(fail("peer nonce was used before (replayed challenge)"))
        }

        peerNonce = nonce.copyOf()
        this.peerSessionId = peerSessionId
        val k = KeyDerivation.derive(code, localNonce, nonce, localSessionId, peerSessionId, iterations)
        keys = k
        val mine = KeyDerivation.hmac(k.auth, transcript(localNonce, nonce, localSessionId, peerSessionId))
        ourResponse = mine

        val out = mutableListOf<Outcome>(Outcome.SendResponse(mine))
        // The peer's response can overtake its own derivation here; settle it now.
        pendingPeerResponse?.let { pending ->
            pendingPeerResponse = null
            verify(pending)?.let(out::add)
        }
        return out
    }

    /** The peer's response arrived. */
    fun onPeerResponse(mac: ByteArray): Outcome {
        if (state != State.AwaitingPeer) return Outcome.None
        if (mac.size != KeyDerivation.KEY_BYTES) return Outcome.None
        if (keys == null) {
            // Their response got here before their challenge finished deriving. Hold it.
            pendingPeerResponse = mac.copyOf()
            return Outcome.None
        }
        return verify(mac) ?: Outcome.None
    }

    private fun verify(mac: ByteArray): Outcome? {
        val k = keys ?: return null
        val n = peerNonce ?: return null
        // What the peer would have computed, from *its* point of view: its nonce first.
        val expected = KeyDerivation.hmac(k.auth, transcript(n, localNonce, peerSessionId, localSessionId))
        return if (KeyDerivation.constantTimeEquals(expected, mac)) {
            state = State.Authenticated
            Outcome.Authenticated
        } else {
            state = State.Failed
            close()
            Outcome.WrongCode
        }
    }

    private fun fail(reason: String): Outcome {
        state = State.Failed
        close()
        return Outcome.Rejected(reason)
    }

    private fun transcript(senderNonce: ByteArray, receiverNonce: ByteArray, senderSid: Long, receiverSid: Long): ByteArray {
        val tag = TAG
        val buf = ByteBuffer.allocate(tag.size + senderNonce.size + receiverNonce.size + 16)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(tag).put(senderNonce).put(receiverNonce).putLong(senderSid).putLong(receiverSid)
        return buf.array()
    }

    private companion object {
        val TAG = "rider-intercom/v1/auth-response".toByteArray(Charsets.US_ASCII)
    }
}
