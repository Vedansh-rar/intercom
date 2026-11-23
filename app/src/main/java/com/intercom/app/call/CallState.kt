package com.intercom.app.call

import com.intercom.app.floor.FloorState
import com.intercom.app.group.GroupPeer
import com.intercom.app.location.LocationState
import com.intercom.app.location.RiderDistance
import com.intercom.app.protocol.PeerInfo
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
    /** What the GPS is doing; [LocationState.Off] unless "Share my location" is on. */
    val location: LocationState = LocationState.Off,
    /** Distance to the buddy in a two-rider call; null if sharing is off or nothing is known yet. */
    val buddy: RiderDistance? = null,
    /** The "buddy falling behind" alert is active. */
    val fallingBehind: Boolean = false,
    /**
     * This rider's own mic is off: nothing is sent. Not the same as [muted], which means someone
     * else holds the floor. Lives in the call controller, so the screen, the notification and a
     * headset button all see the same value.
     */
    val micMuted: Boolean = false,
    /** Deafened: nothing is played to this rider, and the mic is off too. Implies [micMuted]. */
    val deafened: Boolean = false,
    /** What the buddy says about themselves (muted or deafened). Two-rider calls only. */
    val peerStatus: PeerInfo.Status = PeerInfo.Status.Ok,
    /** An SOS received and not yet dismissed. */
    val sosIn: SosIncoming? = null,
    /** An SOS this rider sent, and how it is going. */
    val sosOut: SosOutgoing? = null,
    /** True when an SOS can be sent right now (a buddy or group that understands it). */
    val sosAvailable: Boolean = false,
) {
    val isConnected: Boolean get() = phase == CallPhase.Active
    val canTalk: Boolean get() = isConnected && floor == FloorState.Talking
}

/** An SOS from another rider, with where it is relative to this rider if both positions are known. */
data class SosIncoming(
    val from: String,
    val latDeg: Double?,
    val lonDeg: Double?,
    /** When the position (or, with none, the alert) was made, on the sender's clock. */
    val atMs: Long,
    /** How old the position already was when it was sent, in seconds, if the sender said. */
    val positionAgeSec: Int? = null,
    /** Metres and compass bearing from this rider to them, if both positions are known. */
    val meters: Double? = null,
    val bearingDeg: Double? = null,
)

/** The state of an SOS this rider sent. */
data class SosOutgoing(
    val startedAtMs: Long,
    /** False while it waits for a link; true once it has gone out at least once. */
    val sent: Boolean,
    /** It ran out of time with no link. */
    val failed: Boolean = false,
    /** Whether it carried a position. */
    val withPosition: Boolean = false,
)
