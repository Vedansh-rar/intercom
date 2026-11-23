package com.intercom.app.group

import com.intercom.app.protocol.Message
import com.intercom.app.transport.FrameReader
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Nearby Connections delivers whole payloads, so one payload is one framed message. This
 * reuses the same [FrameReader] the stream transports use, so framing rules (SYNC byte,
 * 7-byte header, MAX_PAYLOAD) are identical on every radio.
 */
object FrameBytes {
    /** The message in [bytes], or null if it is not a single well-formed frame. */
    fun parse(bytes: ByteArray): Message? = try {
        FrameReader(ByteArrayInputStream(bytes)).next()
    } catch (e: IOException) {
        null
    }
}
