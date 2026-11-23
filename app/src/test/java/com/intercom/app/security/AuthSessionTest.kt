package com.intercom.app.security

import com.intercom.app.security.AuthSession.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionTest {
    private fun code(s: String) = (RideCode.parse(s) as RideCode.Parsed.Valid).code
    private val good = code("amber anchor apple arrow aspen")
    private val bad = code("amber anchor apple arrow atlas")

    private val sidA = 1111L
    private val sidB = 2222L
    private val iters = 10

    private fun a(c: RideCode = good, cache: NonceCache = NonceCache()) =
        AuthSession(c, sidA, cache, iters)

    private fun b(c: RideCode = good, cache: NonceCache = NonceCache()) =
        AuthSession(c, sidB, cache, iters)

    private fun List<Outcome>.response(): ByteArray =
        (first { it is Outcome.SendResponse } as Outcome.SendResponse).mac

    /** Runs the whole exchange in order and returns each side's final verdict. */
    private fun run(x: AuthSession, y: AuthSession): Pair<Outcome?, Outcome?> {
        val xOut = x.onPeerChallenge(y.localNonce, y.localSessionId)
        val yOut = y.onPeerChallenge(x.localNonce, x.localSessionId)
        val xv = x.onPeerResponse(yOut.response())
        val yv = y.onPeerResponse(xOut.response())
        return xv to yv
    }

    // ---- success -------------------------------------------------------------

    @Test fun handshakeSucceedsWithTheSameCode() {
        val x = a(); val y = b()
        val (xv, yv) = run(x, y)
        assertTrue(xv is Outcome.Authenticated)
        assertTrue(yv is Outcome.Authenticated)
        assertEquals(AuthSession.State.Authenticated, x.state)
        assertEquals(AuthSession.State.Authenticated, y.state)
    }

    @Test fun ciphersFromASuccessfulHandshakeInteroperate() {
        val x = a(); val y = b()
        run(x, y)
        val cx = x.newCipher()!!
        val cy = y.newCipher()!!
        val msg = "hello rider".toByteArray()
        val s1 = cx.seal(1, 5, 0, msg)
        assertArrayEquals(msg, cy.open(1, 5, s1.flags, s1.payload))
        val s2 = cy.seal(1, 5, 0, msg)
        assertArrayEquals(msg, cx.open(1, 5, s2.flags, s2.payload))
    }

    @Test fun noCipherBeforeAuthentication() {
        val x = a()
        assertNull(x.newCipher())
        x.onPeerChallenge(b().localNonce, sidB)
        assertNull("response sent but peer not yet proven", x.newCipher())
    }

    // ---- wrong passphrase ------------------------------------------------------

    @Test fun wrongPassphraseFailsOnBothSidesAndYieldsNoCipher() {
        val x = a(good); val y = b(bad)
        val (xv, yv) = run(x, y)
        assertTrue(xv is Outcome.WrongCode)
        assertTrue(yv is Outcome.WrongCode)
        assertEquals(AuthSession.State.Failed, x.state)
        assertNull(x.newCipher())
        assertNull(y.newCipher())
    }

    @Test fun aFailedSessionStaysFailed() {
        val x = a(good); val y = b(bad)
        run(x, y)
        assertTrue(x.onPeerChallenge(y.localNonce, y.localSessionId).isEmpty())
        assertTrue(x.onPeerResponse(ByteArray(32)) is Outcome.None)
    }

    @Test fun theSessionIdIsBoundIntoTheProof() {
        // x is told the peer's session id is 9999, but the peer used 2222 in its own MAC.
        val x = a(); val y = b()
        val xOut = x.onPeerChallenge(y.localNonce, 9999L)
        val yOut = y.onPeerChallenge(x.localNonce, x.localSessionId)
        assertTrue(x.onPeerResponse(yOut.response()) is Outcome.WrongCode)
        assertTrue(y.onPeerResponse(xOut.response()) is Outcome.WrongCode)
    }

    @Test fun anOutsiderWhoGuessesWrongGetsNowhere() {
        val x = a()
        x.onPeerChallenge(ByteArray(16) { 9 }, sidB)
        assertTrue(x.onPeerResponse(ByteArray(32) { 1 }) is Outcome.WrongCode)
    }

    // ---- replayed / reflected nonces -------------------------------------------

    @Test fun reflectedChallengeIsRejected() {
        val x = a()
        val out = x.onPeerChallenge(x.localNonce.copyOf(), sidB)
        assertTrue(out.single() is Outcome.Rejected)
        assertEquals(AuthSession.State.Failed, x.state)
    }

    @Test fun aChallengeSeenInAnEarlierConnectionIsRejected() {
        val cache = NonceCache()
        val y1 = b()
        a(cache = cache).onPeerChallenge(y1.localNonce, sidB)
        // Same cache = same controller, next connection. The attacker replays y1's challenge.
        val second = a(cache = cache)
        assertTrue(second.onPeerChallenge(y1.localNonce, sidB).single() is Outcome.Rejected)
    }

    @Test fun aRecordedResponseCannotBeReplayedIntoANewConnection() {
        // Connection 1, recorded in full.
        val x1 = a(); val y1 = b()
        val yOut = y1.onPeerChallenge(x1.localNonce, sidA)
        x1.onPeerChallenge(y1.localNonce, sidB)
        val recordedResponse = yOut.response()
        val recordedChallenge = y1.localNonce

        // Connection 2: fresh nonce on our side. The attacker replays challenge + response
        // (fresh cache so the nonce cache is not what saves us - the MAC must).
        val x2 = a()
        x2.onPeerChallenge(recordedChallenge, sidB)
        assertTrue(x2.onPeerResponse(recordedResponse) is Outcome.WrongCode)
    }

    // ---- ordering and retransmission --------------------------------------------

    @Test fun duplicateChallengeGetsTheSameResponseAgain() {
        val x = a(); val y = b()
        val first = x.onPeerChallenge(y.localNonce, y.localSessionId).response()
        val again = x.onPeerChallenge(y.localNonce, y.localSessionId).response()
        assertArrayEquals(first, again)
    }

    @Test fun aDifferentChallengeMidHandshakeIsIgnored() {
        val x = a(); val y = b()
        x.onPeerChallenge(y.localNonce, y.localSessionId)
        assertTrue(x.onPeerChallenge(ByteArray(16) { 42 }, sidB).isEmpty())
        assertEquals(AuthSession.State.AwaitingPeer, x.state)
    }

    @Test fun aResponseThatArrivesBeforeTheChallengeIsHeldThenChecked() {
        val x = a(); val y = b()
        val yOut = y.onPeerChallenge(x.localNonce, x.localSessionId)
        assertTrue(x.onPeerResponse(yOut.response()) is Outcome.None)
        val out = x.onPeerChallenge(y.localNonce, y.localSessionId)
        assertTrue("our response goes first", out.first() is Outcome.SendResponse)
        assertTrue(out.last() is Outcome.Authenticated)
    }

    @Test fun anEarlyWrongResponseStillSendsOursBeforeFailing() {
        val x = a(good); val y = b(bad)
        val yOut = y.onPeerChallenge(x.localNonce, x.localSessionId)
        x.onPeerResponse(yOut.response())
        val out = x.onPeerChallenge(y.localNonce, y.localSessionId)
        assertTrue(out.first() is Outcome.SendResponse)
        assertTrue(out.last() is Outcome.WrongCode)
    }

    @Test fun malformedMessagesAreIgnored() {
        val x = a()
        assertTrue(x.onPeerChallenge(ByteArray(3), sidB).isEmpty())
        assertTrue(x.onPeerResponse(ByteArray(5)) is Outcome.None)
        assertEquals(AuthSession.State.AwaitingPeer, x.state)
    }

    @Test fun challengesAreRandom() {
        val nonces = (1..100).map { a().localNonce.joinToString("") { b -> "%02x".format(b) } }.toSet()
        assertEquals(100, nonces.size)
    }

    @Test fun closeWipesKeys() {
        val x = a(); val y = b()
        run(x, y)
        assertNotNull(x.newCipher())
        x.close()
        assertNull(x.newCipher())
    }

    @Test fun directionsDifferBetweenTheTwoSides() {
        // Whatever the nonces are, one side must send with 0 and the other with 1, or both
        // phones would encrypt with the same (key, nonce) pair.
        repeat(40) {
            val x = a(); val y = b()
            run(x, y)
            val sx = x.newCipher()!!.seal(1, 0, 0, byteArrayOf(1))
            val sy = y.newCipher()!!.seal(1, 0, 0, byteArrayOf(1))
            // Same plaintext, same counter, same key: ciphertext must still differ.
            assertTrue(!sx.payload.contentEquals(sy.payload))
        }
    }
}
