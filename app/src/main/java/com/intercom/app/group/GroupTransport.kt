package com.intercom.app.group

import com.intercom.app.transport.PeerLink

/**
 * What a group ride needs from whatever carries it: the lifecycle and sending of a
 * [PeerLink], the neighbour table of a [MeshPort], and a [listener] that names the
 * neighbour a frame came from. [GroupLink] (Nearby Connections, over the air) and
 * [InternetLink] (through a relay server) both provide it; the audio pipeline, the mixer and
 * the floor do not know which one is underneath.
 */
interface GroupTransport : PeerLink, MeshPort {
    var listener: GroupLink.Listener?
}
