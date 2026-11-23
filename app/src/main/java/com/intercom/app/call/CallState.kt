package com.intercom.app.call

import com.intercom.app.floor.FloorState
import com.intercom.app.group.GroupPeer
import com.intercom.app.security.ChannelSecurity
import com.intercom.app.transport.LinkState

/** The session state machine the service and the UI both read. */
enum class CallPhase {
    Idle,
    Starting,
    Connecting,
    Active,
    Reconnecting,
    Ended,
    Error,
}

/** Everything the call screen draws, in one immutable snapshot. */
data class CallStatus(
    val phase: CallPhase = CallPhase.Idle,
    val link: LinkState = LinkState.Idle,
    val floor: FloorState = FloorState.Idle,
    val statusText: String = "Not connected",
    val peerName: String? = null,
    val isHost: Boolean = false,
    val rttMs: Int = -1,
    val lossPercent: Double = 0.0,
    val jitterDepth: Int = 0,
    val pushToTalk: Boolean = false,
    val muted: Boolean = false,
    val focusHeld: Boolean = false,
    val elapsedSeconds: Long = 0,
    val error: String? = null,
    val security: ChannelSecurity = ChannelSecurity.Pending,
    /** True on a group ride. The two-rider fields above keep their meaning for two riders. */
    val group: Boolean = false,
    val peers: List<GroupPeer> = emptyList(),
) {
    val isConnected: Boolean get() = phase == CallPhase.Active
    val canTalk: Boolean get() = isConnected && floor == FloorState.Talking
}
