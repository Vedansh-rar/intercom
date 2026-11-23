package com.intercom.app.group

/**
 * What a phone advertises on Nearby Connections: its display name and random sender ID,
 * joined by '#'. It carries no secret and is never trusted: anyone can advertise anything,
 * so it is used only to decide which of two phones asks the other to connect. The name a
 * rider sees on screen comes later, from an authenticated PEER_INFO.
 */
object GroupEndpointName {
    const val NAME_CHARS = 24

    fun format(name: String, id: Int): String =
        "${name.replace('#', ' ').take(NAME_CHARS)}#${SenderId.hex(id)}"

    /** (name, id) from an advertised name; the id is null if the name is malformed. */
    fun parse(n: String): Pair<String, Int?> {
        val i = n.lastIndexOf('#')
        if (i < 0 || n.length - i - 1 != 8) return n to null
        val id = n.substring(i + 1).toLongOrNull(16)?.toInt()
        return n.substring(0, i) to id
    }

    /** True when the phone with [selfId] should be the one to ask [peerId] to connect. */
    fun shouldInitiate(selfId: Int, peerId: Int): Boolean = Integer.compareUnsigned(selfId, peerId) < 0
}
