package com.intercom.app.relay

import java.net.URI

/**
 * The relay address the rider types. Only `wss://` is accepted: voice is encrypted end to
 * end anyway, but the room id, rider addresses and timing should not cross the network in the
 * clear either, and a plain `ws://` address would be an easy mistake to make on a phone.
 */
object RelayUrl {

    /** The normalised base, `wss://host` or `wss://host:port`, or null if [raw] is not acceptable. */
    fun parse(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 200) return null
        if (s.any { it.isWhitespace() || it.isISOControl() }) return null
        val u = try { URI(s) } catch (e: Exception) { return null }
        if (!"wss".equals(u.scheme, ignoreCase = true)) return null
        if (u.userInfo != null || u.query != null || u.fragment != null) return null
        if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) return null
        val host = u.host?.lowercase() ?: return null
        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".")) return null
        val port = u.port
        if (port != -1 && port !in 1..65535) return null
        return if (port == -1 || port == 443) "wss://$host" else "wss://$host:$port"
    }

    /** The socket address for [room] on the relay at [base] (a value from [parse]). */
    fun endpoint(base: String, room: String): String = "$base/ws?room=$room"
}
