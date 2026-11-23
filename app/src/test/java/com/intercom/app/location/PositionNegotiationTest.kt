package com.intercom.app.location

import com.intercom.app.protocol.Capabilities
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.NegotiatedFeatures
import com.intercom.app.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Position is a negotiated capability: a buddy that cannot do it is never sent one. */
class PositionNegotiationTest {

    private fun hello(version: Int, caps: Int) = Hello(
        version = version, sampleRate = 16000, frameSamples = 320, codec = Hello.CODEC_ADPCM,
        sessionId = 1L, riderName = "x", capabilities = caps,
    )

    @Test fun theBitIsOnlySetWhenOffered() {
        assertEquals(0, Capabilities.local(false) and Capabilities.POSITION)
        assertEquals(0, Capabilities.local(true, true) and Capabilities.POSITION)
        assertEquals(Capabilities.POSITION, Capabilities.local(false, positionAvailable = true) and Capabilities.POSITION)
    }

    @Test fun existingBitsAreUntouched() {
        val all = Capabilities.local(true, true, true)
        assertEquals(Capabilities.OPUS or Capabilities.AUTH or Capabilities.POSITION, all)
    }

    @Test fun twoCapablePhonesNegotiatePosition() {
        val f = NegotiatedFeatures.negotiate(
            Capabilities.local(true, true, true), hello(Protocol.VERSION, Capabilities.POSITION or Capabilities.AUTH),
        )
        assertTrue(f.position)
    }

    @Test fun aBuddyWithoutThePositionBitGetsNone() {
        val f = NegotiatedFeatures.negotiate(
            Capabilities.local(true, true, true), hello(Protocol.VERSION, Capabilities.OPUS or Capabilities.AUTH),
        )
        assertFalse(f.position)
    }

    @Test fun aV1BuddyGetsNone() {
        val f = NegotiatedFeatures.negotiate(Capabilities.local(true, true, true), hello(1, 0))
        assertFalse(f.position)
        assertTrue(f.isLegacyPeer)
    }

    @Test fun aPhoneThatDoesNotOfferItNeverGetsIt() {
        val f = NegotiatedFeatures.negotiate(
            Capabilities.local(true, true), hello(Protocol.VERSION, Capabilities.POSITION),
        )
        assertFalse(f.position)
    }
}
