package com.intercom.app.relay

/** Why a relay connection ended; decides whether and how fast to try again. */
enum class RelayFailure {
    /** The connection dropped (no signal, tunnel, server restart). Retry soon. */
    Lost,

    /** The relay is rate limiting or overloaded. Retry, slowly. */
    Busy,

    /** The room already has the most riders it allows. Retry, slowly. */
    Full,

    /** The relay does not accept this address or room. Retrying cannot help. */
    Refused,
}

interface RelaySocketEvents {
    fun onOpen()
    fun onMessage(bytes: ByteArray)
    /** Called once per socket, after which it must not be used. */
    fun onClosed(failure: RelayFailure)
}

interface RelaySocket {
    /** Queues one binary message. False if the socket is closed or its queue is full. */
    fun send(bytes: ByteArray): Boolean
    fun close()
}

/** Opens sockets to one relay and room. Kept as an interface so the link can be tested on a JVM. */
fun interface RelayConnector {
    fun connect(events: RelaySocketEvents): RelaySocket
}
