package com.intercom.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayUrlTest {

    @Test fun acceptsAndNormalisesWss() {
        assertEquals("wss://relay.example.com", RelayUrl.parse("wss://relay.example.com"))
        assertEquals("wss://relay.example.com", RelayUrl.parse("  WSS://Relay.Example.com/  "))
        assertEquals("wss://relay.example.com", RelayUrl.parse("wss://relay.example.com:443"))
        assertEquals("wss://relay.example.com:8443", RelayUrl.parse("wss://relay.example.com:8443"))
        assertEquals("wss://my-app.fly.dev", RelayUrl.parse("wss://my-app.fly.dev"))
        assertEquals("wss://203.0.113.7:9000", RelayUrl.parse("wss://203.0.113.7:9000"))
    }

    @Test fun refusesAnythingNotTls() {
        assertNull(RelayUrl.parse("ws://relay.example.com"))
        assertNull(RelayUrl.parse("http://relay.example.com"))
        assertNull(RelayUrl.parse("https://relay.example.com"))
        assertNull(RelayUrl.parse("relay.example.com"))
        assertNull(RelayUrl.parse("//relay.example.com"))
    }

    @Test fun refusesEmptyAndNull() {
        assertNull(RelayUrl.parse(null))
        assertNull(RelayUrl.parse(""))
        assertNull(RelayUrl.parse("   "))
        assertNull(RelayUrl.parse("wss://"))
    }

    @Test fun refusesCredentialsPathsQueriesAndFragments() {
        assertNull(RelayUrl.parse("wss://user:pw@relay.example.com"))
        assertNull(RelayUrl.parse("wss://relay.example.com@evil.com"))
        assertNull(RelayUrl.parse("wss://relay.example.com/ws"))
        assertNull(RelayUrl.parse("wss://relay.example.com/?room=1"))
        assertNull(RelayUrl.parse("wss://relay.example.com/#x"))
    }

    @Test fun refusesBadPortsAndHosts() {
        assertNull(RelayUrl.parse("wss://relay.example.com:0"))
        assertNull(RelayUrl.parse("wss://relay.example.com:99999"))
        assertNull(RelayUrl.parse("wss://relay .example.com"))
        assertNull(RelayUrl.parse("wss://.example.com"))
        assertNull(RelayUrl.parse("wss://relay.example.com\n"))
        assertNull(RelayUrl.parse("wss://" + "a".repeat(300)))
    }

    @Test fun buildsTheSocketAddress() {
        assertEquals("wss://r.example.com/ws?room=abc", RelayUrl.endpoint("wss://r.example.com", "abc"))
    }
}
