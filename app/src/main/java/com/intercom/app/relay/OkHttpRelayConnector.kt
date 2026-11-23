package com.intercom.app.relay

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The only code that opens an internet connection for a ride: one TLS WebSocket to the
 * relay's `/ws?room=...`. [url] must be a `wss://` address from [RelayUrl]; plain text is
 * refused here again, in case a caller skipped the check.
 *
 * Pings every 20 s both keep a mobile network's NAT entry alive and notice a dead connection
 * (a tunnel, a handover) within about 40 s.
 */
class OkHttpRelayConnector(
    private val url: String,
    private val client: OkHttpClient = defaultClient(),
) : RelayConnector {

    override fun connect(events: RelaySocketEvents): RelaySocket {
        require(url.startsWith("wss://")) { "relay must be wss://" }
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onMessage(bytes.toByteArray())

            // The relay speaks binary only; text is ignored.
            override fun onMessage(webSocket: WebSocket, text: String) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                events.onClosed(classify(code, null))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                events.onClosed(classify(0, response?.code))
        })
        return object : RelaySocket {
            override fun send(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())
            override fun close() { ws.close(1000, "bye") }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /** From a close code or an HTTP status on a failed upgrade. Pure, so it is unit-tested. */
        fun classify(closeCode: Int, httpStatus: Int?): RelayFailure = when {
            httpStatus == 403 -> RelayFailure.Full
            httpStatus == 429 || httpStatus == 503 -> RelayFailure.Busy
            httpStatus != null && httpStatus in 400..499 -> RelayFailure.Refused
            closeCode == 1008 -> RelayFailure.Busy
            closeCode == 1003 || closeCode == 1009 -> RelayFailure.Refused
            else -> RelayFailure.Lost
        }
    }
}
