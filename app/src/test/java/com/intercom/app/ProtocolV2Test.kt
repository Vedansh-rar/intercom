package com.intercom.app

import com.intercom.app.protocol.AuthChallenge
import com.intercom.app.protocol.AuthResponse
import com.intercom.app.protocol.Capabilities
import com.intercom.app.protocol.Codec
import com.intercom.app.protocol.CodecId
import com.intercom.app.protocol.Hello
import com.intercom.app.protocol.NegotiatedFeatures
import com.intercom.app.protocol.PeerInfo
import com.intercom.app.protocol.PositionReport
import com.intercom.app.protocol.Protocol
import com.intercom.app.protocol.SosAlert
import com.intercom.app.transport.FrameReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolV2Test {

    private fun hello(version: Int, caps: Int = 0, name: String = "Arun") = Hello(
        version = version,
        sampleRate = 16000,
        frameSamples = 320,
        codec = Hello.CODEC_ADPCM,
        sessionId = 99L,
        riderName = name,
        capabilities = caps,
    )

    // ------------------------------------------------------------ versions

    @Test
    fun `this build speaks v2 and accepts v1`() {
        assertEquals(2, Protocol.VERSION)
        assertTrue(Protocol.isSupportedVersion(1))
        assertTrue(Protocol.isSupportedVersion(2))
        assertFalse(Protocol.isSupportedVersion(0))
        assertFalse(Protocol.isSupportedVersion(3))
    }

    // ------------------------------------------------------------ HELLO

    @Test
    fun `v2 hello round trips with capabilities`() {
        val caps = Capabilities.OPUS or Capabilities.AUTH
        val h = hello(2, caps)
        val bytes = h.toBytes()
        assertEquals(Protocol.HELLO_V2_SIZE, bytes.size)
        assertEquals(h, Hello.parse(bytes))
    }

    @Test
    fun `v2 hello keeps v1 in the head so an old build accepts it`() {
        val bytes = hello(2, Capabilities.KNOWN).toBytes()
        // Bytes 0-1 are the version field a v1 build compares with == 1.
        assertEquals(0, bytes[0].toInt())
        assertEquals(1, bytes[1].toInt())
    }

    @Test
    fun `a v1 parser reading a v2 hello gets the v1 fields intact`() {
        val sent = hello(2, Capabilities.KNOWN, "Meera")
        // Emulate the v1 parser: it only ever looked at the first 32 bytes.
        val v1View = sent.toBytes().copyOf(Protocol.HELLO_SIZE)
        val parsed = Hello.parse(v1View)!!
        assertEquals(1, parsed.version)
        assertEquals(0, parsed.capabilities)
        assertEquals("Meera", parsed.riderName)
        assertEquals(99L, parsed.sessionId)
        assertEquals(16000, parsed.sampleRate)
        assertEquals(320, parsed.frameSamples)
    }

    @Test
    fun `a v1 hello parses as version 1 with no capabilities`() {
        val parsed = Hello.parse(hello(1).toBytes())!!
        assertEquals(1, parsed.version)
        assertEquals(0, parsed.capabilities)
    }

    @Test
    fun `a v1 hello with trailing junk is not mistaken for v2`() {
        val padded = hello(1).toBytes().copyOf(Protocol.HELLO_V2_SIZE) // zero tail, no magic
        val parsed = Hello.parse(padded)!!
        assertEquals(1, parsed.version)
        assertEquals(0, parsed.capabilities)
    }

    @Test
    fun `hello extension with an unknown future version is read as v1`() {
        val bytes = hello(2, Capabilities.KNOWN).toBytes()
        bytes[Protocol.HELLO_SIZE + 2] = 1 // extension claiming version 1
        assertEquals(1, Hello.parse(bytes)!!.version)
    }

    @Test
    fun `a truncated hello is rejected`() {
        assertNull(Hello.parse(ByteArray(Protocol.HELLO_SIZE - 1)))
    }

    @Test
    fun `hello travels through the frame reader`() {
        val framed = Codec.encode(Protocol.TYPE_HELLO, 1, 0, hello(2, Capabilities.POSITION).toBytes())
        val m = FrameReader(ByteArrayInputStream(framed)).next()!!
        assertEquals(Protocol.TYPE_HELLO, m.type)
        assertEquals(Capabilities.POSITION, Hello.parse(m.payload)!!.capabilities)
    }

    // ------------------------------------------------------- negotiation

    @Test
    fun `v2 with v1 negotiates ADPCM and nothing else`() {
        val f = NegotiatedFeatures.negotiate(Capabilities.KNOWN, hello(1))
        assertEquals(CodecId.Adpcm, f.codec)
        assertFalse(f.position)
        assertFalse(f.auth)
        assertFalse(f.group)
        assertTrue(f.isLegacyPeer)
    }

    @Test
    fun `a v1 peer cannot gain capabilities from stray bits`() {
        val sneaky = hello(1).copy(capabilities = Capabilities.KNOWN)
        val f = NegotiatedFeatures.negotiate(Capabilities.KNOWN, sneaky)
        assertEquals(CodecId.Adpcm, f.codec)
        assertFalse(f.auth)
    }

    @Test
    fun `v2 with v2 uses the intersection`() {
        val local = Capabilities.OPUS or Capabilities.POSITION or Capabilities.AUTH
        val peer = hello(2, Capabilities.OPUS or Capabilities.AUTH or Capabilities.GROUP)
        val f = NegotiatedFeatures.negotiate(local, peer)
        assertEquals(CodecId.Opus, f.codec)
        assertTrue(f.auth)
        assertFalse(f.position) // we have it, they do not
        assertFalse(f.group)    // they have it, we do not
        assertFalse(f.isLegacyPeer)
    }

    @Test
    fun `opus needs both sides`() {
        assertEquals(
            CodecId.Adpcm,
            NegotiatedFeatures.negotiate(Capabilities.OPUS, hello(2, 0)).codec,
        )
        assertEquals(
            CodecId.Adpcm,
            NegotiatedFeatures.negotiate(0, hello(2, Capabilities.OPUS)).codec,
        )
        assertEquals(
            CodecId.Opus,
            NegotiatedFeatures.negotiate(Capabilities.OPUS, hello(2, Capabilities.OPUS)).codec,
        )
    }

    @Test
    fun `unknown capability bits from a newer peer are ignored`() {
        val f = NegotiatedFeatures.negotiate(0xFFFF, hello(2, 0xFFF0 or Capabilities.AUTH))
        assertTrue(f.auth)
        assertFalse(f.position)
    }

    @Test
    fun `this build does not yet advertise unimplemented features`() {
        assertEquals(0, Capabilities.LOCAL and Capabilities.KNOWN.inv())
        assertEquals(emptyList<String>(), Capabilities.names(Capabilities.LOCAL))
    }

    @Test
    fun `legacy defaults are the original call`() {
        val f = NegotiatedFeatures.LEGACY
        assertEquals(CodecId.Adpcm, f.codec)
        assertFalse(f.position || f.auth || f.group)
    }

    // ------------------------------------------------------ message types

    @Test
    fun `new message types are distinct and named`() {
        val types = listOf(
            Protocol.TYPE_AUDIO, Protocol.TYPE_PING, Protocol.TYPE_FLOOR_TAKEN,
            Protocol.TYPE_FLOOR_RELEASE, Protocol.TYPE_FLOOR_DENIED, Protocol.TYPE_END,
            Protocol.TYPE_PONG, Protocol.TYPE_SWITCH, Protocol.TYPE_POSITION,
            Protocol.TYPE_AUTH_CHALLENGE, Protocol.TYPE_AUTH_RESPONSE, Protocol.TYPE_SOS,
            Protocol.TYPE_PEER_INFO, Protocol.TYPE_HELLO,
        )
        assertEquals(types.size, types.toSet().size)
        assertEquals(0x09.toByte(), Protocol.TYPE_POSITION) // v1 numbers are not shifted
        assertEquals(0x08.toByte(), Protocol.TYPE_SWITCH)
        assertEquals(0x10.toByte(), Protocol.TYPE_HELLO)
        for (t in types) assertFalse(Protocol.typeName(t), Protocol.typeName(t).startsWith("UNKNOWN"))
        assertEquals("POSITION", Protocol.typeName(Protocol.TYPE_POSITION))
        assertEquals("SOS", Protocol.typeName(Protocol.TYPE_SOS))
        assertTrue(Protocol.typeName(0x7F).startsWith("UNKNOWN"))
    }

    private fun roundTrip(type: Byte, payload: ByteArray): ByteArray {
        val framed = Codec.encode(type, 5, 0, payload)
        val m = FrameReader(ByteArrayInputStream(framed)).next()
        assertNotNull(m)
        assertEquals(type, m!!.type)
        return m.payload
    }

    @Test
    fun `position report round trips`() {
        val p = PositionReport(
            latE7 = 285_000_000, lonE7 = 770_000_000, speedCms = 2222,
            headingDeci = 1795, accuracyM = 8, timestampMs = 1_700_000_123_456L,
        )
        assertEquals(PositionReport.SIZE, p.toBytes().size)
        assertEquals(p, PositionReport.parse(roundTrip(Protocol.TYPE_POSITION, p.toBytes())))
    }

    @Test
    fun `position report handles negative coordinates and clamps ranges`() {
        val south = PositionReport(-338_688_000, -1_512_000_000, 0, 0, 0, 1L)
        assertEquals(south, PositionReport.parse(south.toBytes()))
        val clamped = PositionReport(0, 0, 999_999, 99_999, 999_999, 0).let {
            PositionReport.parse(it.toBytes())!!
        }
        assertEquals(0xFFFF, clamped.speedCms)
        assertEquals(3599, clamped.headingDeci)
        assertEquals(0xFFFF, clamped.accuracyM)
    }

    @Test
    fun `truncated payloads parse to null instead of throwing`() {
        assertNull(PositionReport.parse(ByteArray(PositionReport.SIZE - 1)))
        assertNull(AuthChallenge.parse(ByteArray(AuthChallenge.NONCE_SIZE - 1)))
        assertNull(AuthResponse.parse(ByteArray(AuthResponse.MAC_SIZE - 1)))
        assertNull(SosAlert.parse(ByteArray(SosAlert.SIZE - 1)))
        assertNull(PeerInfo.parse(ByteArray(1)))
        assertNull(PeerInfo.parse(byteArrayOf(0, 10, 65))) // claims 10 name bytes, has 1
    }

    @Test
    fun `auth challenge and response round trip`() {
        val c = AuthChallenge(ByteArray(16) { (it * 7).toByte() })
        assertEquals(c, AuthChallenge.parse(roundTrip(Protocol.TYPE_AUTH_CHALLENGE, c.toBytes())))
        val r = AuthResponse(ByteArray(32) { (255 - it).toByte() })
        assertEquals(r, AuthResponse.parse(roundTrip(Protocol.TYPE_AUTH_RESPONSE, r.toBytes())))
    }

    @Test
    fun `auth payloads enforce their sizes`() {
        assertEquals(
            true,
            runCatching { AuthChallenge(ByteArray(15)) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertEquals(
            true,
            runCatching { AuthResponse(ByteArray(31)) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `sos round trips with and without a position`() {
        val with = SosAlert(285_000_000, 770_000_000, 42L)
        assertEquals(with, SosAlert.parse(roundTrip(Protocol.TYPE_SOS, with.toBytes())))
        val without = SosAlert(null, null, 43L)
        val back = SosAlert.parse(roundTrip(Protocol.TYPE_SOS, without.toBytes()))!!
        assertNull(back.latE7)
        assertNull(back.lonE7)
        assertEquals(43L, back.timestampMs)
    }

    @Test
    fun `peer info round trips including non-ascii and over-long names`() {
        val a = PeerInfo(PeerInfo.Status.Muted, "Ravi")
        assertEquals(a, PeerInfo.parse(roundTrip(Protocol.TYPE_PEER_INFO, a.toBytes())))
        val hindi = PeerInfo(PeerInfo.Status.Ok, "राइडर")
        assertEquals(hindi, PeerInfo.parse(hindi.toBytes()))
        val long = PeerInfo(PeerInfo.Status.Busy, "x".repeat(60)).toBytes()
        assertEquals("x".repeat(PeerInfo.NAME_BYTES), PeerInfo.parse(long)!!.name)
    }

    @Test
    fun `an unknown status byte falls back to Ok`() {
        assertEquals(PeerInfo.Status.Ok, PeerInfo.parse(byteArrayOf(99, 1, 65))!!.status)
    }

    @Test
    fun `new messages fit well inside the payload limit`() {
        assertTrue(Protocol.HELLO_V2_SIZE <= Protocol.MAX_PAYLOAD)
        assertTrue(PositionReport.SIZE <= Protocol.MAX_PAYLOAD)
        assertTrue(2 + PeerInfo.NAME_BYTES <= Protocol.MAX_PAYLOAD)
    }
}
