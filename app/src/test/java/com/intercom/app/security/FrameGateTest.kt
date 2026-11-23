package com.intercom.app.security

import com.intercom.app.protocol.AuthChallenge
import com.intercom.app.protocol.Capabilities
import com.intercom.app.protocol.Codec
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.Message
import com.intercom.app.protocol.NegotiatedFeatures
import com.intercom.app.protocol.Protocol
import com.intercom.app.security.FrameGate.Result
import com.intercom.app.security.SecurityPolicy.Decision
import com.intercom.app.transport.FrameReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameGateTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val tx = FrameCipher(key, 0)
    private val rx = FrameCipher(key, 1)

    private val payload = ByteArray(40) { (it * 3).toByte() }

    /** Seals, writes to the wire format, and reads it back, as the link would. */
    private fun overTheWire(type: Byte, seq: Int, flags: Byte, body: ByteArray): Message {
        val s = tx.seal(type, seq, flags, body)
        val bytes = Codec.encode(type, seq, s.flags, s.payload)
        return FrameReader(ByteArrayInputStream(bytes)).next()!!
    }

    @Test fun anEncryptedFrameIsDecryptedAndTheCodecBitSurvives() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 77, 1, payload)
        assertTrue(FrameCipher.isEncrypted(m.flags))
        val r = FrameGate.admit(ChannelSecurity.Encrypted, rx, m) as Result.Accept
        assertArrayEquals(payload, r.message.payload)
        assertEquals(1.toByte(), r.message.flags)
        assertEquals(77, r.message.seq)
        assertEquals(Protocol.TYPE_AUDIO, r.message.type)
    }

    @Test fun cleartextAudioInjectedIntoAnEncryptedCallIsRefused() {
        val injected = Message(Protocol.TYPE_AUDIO, 1, 1, payload)
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, rx, injected) is Result.WrongForm)
        val floor = Message(Protocol.TYPE_FLOOR_TAKEN, 1, 0, ByteArray(16))
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, rx, floor) is Result.WrongForm)
    }

    @Test fun aTamperedFrameOnTheWireIsInvalid() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        val bad = m.payload.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        val r = FrameGate.admit(ChannelSecurity.Encrypted, rx, Message(m.type, m.seq, m.flags, bad))
        assertTrue(r is Result.Invalid)
    }

    @Test fun aReplayedFrameIsInvalidTheSecondTime() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, rx, m) is Result.Accept)
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, rx, m) is Result.Invalid)
    }

    @Test fun aHeaderEditedOnTheWireIsInvalid() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        val moved = Message(Protocol.TYPE_FLOOR_RELEASE, m.seq, m.flags, m.payload)
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, rx, moved) is Result.Invalid)
    }

    @Test fun nothingIsAdmittedBeforeTheCheckFinishes() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        val plain = Message(Protocol.TYPE_AUDIO, 5, 0, payload)
        for (state in listOf(ChannelSecurity.Pending, ChannelSecurity.Authenticating)) {
            assertTrue(FrameGate.admit(state, rx, m) is Result.NotReady)
            assertTrue(FrameGate.admit(state, null, plain) is Result.NotReady)
        }
    }

    @Test fun anEncryptedChannelWithoutACipherAdmitsNothing() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        assertTrue(FrameGate.admit(ChannelSecurity.Encrypted, null, m) is Result.NotReady)
    }

    @Test fun anAllowedLegacyBuddyPassesThroughUnchanged() {
        val plain = Message(Protocol.TYPE_AUDIO, 9, 0, payload)
        val r = FrameGate.admit(ChannelSecurity.Unencrypted, null, plain) as Result.Accept
        assertEquals(plain, r.message)
    }

    @Test fun inUnencryptedModeAnEncryptedFrameIsNotMisreadAsAudio() {
        val m = overTheWire(Protocol.TYPE_AUDIO, 5, 0, payload)
        assertTrue(FrameGate.admit(ChannelSecurity.Unencrypted, null, m) is Result.WrongForm)
    }

    @Test fun theEncryptedFlagDoesNotCollideWithTheCodecFlags() {
        // Audio flags today are 0 (ADPCM) and 1 (Opus); the encryption bit is 0x80.
        assertEquals(0, (0 and FrameCipher.ENCRYPTED_FLAG.toInt()))
        assertEquals(0, (1 and FrameCipher.ENCRYPTED_FLAG.toInt()))
        assertFalse(FrameCipher.isEncrypted(0))
        assertFalse(FrameCipher.isEncrypted(1))
        assertTrue(FrameCipher.isEncrypted(0x81.toByte()))
    }

    // ---- policy ---------------------------------------------------------------------

    @Test fun policyMatrix() {
        // hasCode, peerAuth, allowLegacy -> decision
        assertEquals(Decision.Authenticate, SecurityPolicy.decide(true, true, false))
        assertEquals(Decision.Authenticate, SecurityPolicy.decide(true, true, true))
        assertEquals(Decision.RefuseBuddy, SecurityPolicy.decide(true, false, false))
        assertEquals(Decision.ProceedUnencrypted, SecurityPolicy.decide(true, false, true))
        assertEquals(Decision.RefuseBuddy, SecurityPolicy.decide(false, true, false))
        assertEquals(Decision.ProceedUnencrypted, SecurityPolicy.decide(false, true, true))
        assertEquals(Decision.RefuseBuddy, SecurityPolicy.decide(false, false, false))
        assertEquals(Decision.ProceedUnencrypted, SecurityPolicy.decide(false, false, true))
    }

    private fun hello(version: Int, caps: Int) = Hello(version, 16000, 320, 1, 5L, "x", caps)

    @Test fun aV1BuddyIsRefusedByDefault() {
        val f = NegotiatedFeatures.negotiate(Capabilities.local(true, true), hello(1, 0))
        assertFalse(f.auth)
        assertEquals(Decision.RefuseBuddy, SecurityPolicy.decide(true, f.auth, false))
        assertEquals(Decision.ProceedUnencrypted, SecurityPolicy.decide(true, f.auth, true))
    }

    @Test fun aV2BuddyBuiltBeforeAuthIsAlsoRefused() {
        val f = NegotiatedFeatures.negotiate(Capabilities.local(true, true), hello(2, Capabilities.OPUS))
        assertFalse(f.auth)
        assertEquals(Decision.RefuseBuddy, SecurityPolicy.decide(true, f.auth, false))
    }

    @Test fun twoCurrentBuddiesAuthenticate() {
        val caps = Capabilities.local(true, true)
        val f = NegotiatedFeatures.negotiate(caps, hello(2, caps))
        assertTrue(f.auth)
        assertEquals(Decision.Authenticate, SecurityPolicy.decide(true, f.auth, false))
    }

    @Test fun noRideCodeMeansAuthIsNotAdvertised() {
        assertEquals(0, Capabilities.local(false, false) and Capabilities.AUTH)
        assertEquals(Capabilities.AUTH, Capabilities.local(false, true) and Capabilities.AUTH)
        assertEquals(Capabilities.OPUS or Capabilities.AUTH, Capabilities.local(true, true))
        // A phone with no code negotiates auth away even with a capable buddy.
        val f = NegotiatedFeatures.negotiate(Capabilities.local(true, false), hello(2, Capabilities.local(true, true)))
        assertFalse(f.auth)
    }

    // ---- the challenge on the wire ------------------------------------------------

    @Test fun challengeCarriesTheSessionIdAndStaysReadableWithoutIt() {
        val nonce = ByteArray(16) { it.toByte() }
        val withSid = AuthChallenge(nonce, 0x0123456789ABCDEFL)
        assertEquals(24, withSid.toBytes().size)
        assertEquals(withSid, AuthChallenge.parse(withSid.toBytes()))
        val legacy = AuthChallenge.parse(nonce)
        assertNotNull(legacy)
        assertEquals(0L, legacy!!.sessionId)
        assertNull(AuthChallenge.parse(ByteArray(15)))
    }

    @Test fun anEncryptedFrameFitsTheFrameLimit() {
        // The biggest thing the controller seals today is a 16-byte floor message or an
        // audio frame of a few hundred bytes at most; 492 is the hard ceiling.
        val s = tx.seal(Protocol.TYPE_AUDIO, 0, 0, ByteArray(492))
        assertEquals(Protocol.MAX_PAYLOAD, s.payload.size)
        Codec.encode(Protocol.TYPE_AUDIO, 0, s.flags, s.payload) // must not throw
    }
}
