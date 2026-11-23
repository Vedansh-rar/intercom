package com.intercom.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.sp
import com.intercom.app.R
import com.intercom.app.call.CallPhase
import com.intercom.app.call.CallStatus
import com.intercom.app.security.ChannelSecurity
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.floor.FloorState
import com.intercom.app.location.DistanceUnit
import com.intercom.app.util.TalkMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/**
 * The screen the rider glances at from the saddle.
 *
 * The dial's colour is the whole message: orange means the channel is clear, green means
 * you have it, amber means your buddy does. Everything else on the screen is secondary to
 * reading that in under a second, through a visor, at speed.
 */
@Composable
fun CallScreen(
    status: CallStatus,
    riderName: String,
    micLevel: Float,
    talkMode: TalkMode,
    noiseSuppression: NoiseSuppression,
    onCycleNoise: () -> Unit,
    onPushToTalkDown: () -> Unit,
    onPushToTalkUp: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPeerMute: (id: Int, muted: Boolean) -> Unit = { _, _ -> },
    distanceUnit: DistanceUnit = DistanceUnit.Kilometres,
    onToggleMute: () -> Unit = {},
    onToggleDeafen: () -> Unit = {},
    onSendSos: () -> Unit = {},
    onDismissSosOut: () -> Unit = {},
) = WithAccents(host = status.isHost) {
    val accents = LocalAccents.current
    val stateColor = when {
        !status.isConnected -> Brand.Idle
        status.floor == FloorState.Talking -> Brand.Talking
        status.floor == FloorState.Muted -> Brand.Buddy
        else -> accents.base
    }
    val dialColor by animateColorAsState(stateColor, label = "dial")

    val musicIcon = ImageVector.vectorResource(R.drawable.ic_music_note)
    val audioIcon = ImageVector.vectorResource(R.drawable.ic_volume_up)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 26.dp)
    ) {
        BrandHeader(
            trailing = {
                Box(
                    Modifier
                        .size(Comfort.MIN_TOUCH_DP.dp)
                        .clip(CircleShape)
                        .background(Brand.Surface)
                        .clickable(role = Role.Button, onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings),
                        tint = Brand.TextPrimary, modifier = Modifier.size(21.dp),
                    )
                }
            }
        )

        // ---- who you are connected to -------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusPill(
                headline = when {
                    status.group -> if (status.peers.isEmpty()) stringResource(R.string.call_group_ride) else stringResource(R.string.call_group_ride_n, status.peers.size + 1)
                    status.isConnected -> status.peerName?.let { stringResource(R.string.call_linked_with, it) } ?: stringResource(R.string.call_linked)
                    status.isHost -> stringResource(R.string.call_hosting)
                    else -> stringResource(R.string.call_joining)
                },
                detail = status.statusText,
                dotColor = if (status.isConnected) Brand.Talking else Brand.Buddy,
            )
        }

        SecurityNotice(status)
        PeerStatusNote(status)

        // ---- the dial -----------------------------------------------------------
        Spacer(Modifier.height(26.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MicDial(
                    color = dialColor,
                    icon = if (status.floor == FloorState.Muted) MicIcons.MicOff else MicIcons.Mic,
                    active = status.floor != FloorState.Idle,
                    size = 210.dp,
                )
                Spacer(Modifier.height(16.dp))
                DotStatus(
                    text = when {
                        !status.isConnected -> phaseLabel(status)
                        status.floor == FloorState.Talking -> stringResource(R.string.call_you_talking)
                        status.floor == FloorState.Muted -> stringResource(
                            R.string.call_peer_is_talking,
                            status.peerName ?: stringResource(
                                if (status.group) R.string.call_a_rider else R.string.call_buddy
                            ),
                        )
                        else -> stringResource(R.string.call_channel_clear)
                    },
                    color = dialColor,
                    subtitle = when {
                        !status.isConnected ->
                            if (status.isHost) stringResource(R.string.start_ready_host_detail)
                            else stringResource(R.string.call_reaching)
                        status.floor == FloorState.Muted ->
                            if (status.group) stringResource(R.string.call_wait_group) else stringResource(R.string.call_mic_muted_until)
                        else -> stringResource(R.string.call_speak_hint)
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- distance to the other rider(s): only while the rider shares ----------
        if (status.fallingBehind) {
            Box(Modifier.padding(horizontal = 16.dp)) { FallingBehindBanner(farthestRiderName(status)) }
            Spacer(Modifier.height(12.dp))
        }
        if (!status.group && showsLocation(status)) {
            Box(Modifier.padding(horizontal = 16.dp)) { BuddyDistanceCard(status, distanceUnit) }
            Spacer(Modifier.height(16.dp))
        }

        // ---- push to talk, only when that is the chosen mode ---------------------
        if (talkMode == TalkMode.PushToTalk) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Comfort.buttonHeight(92.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .let {
                                if (status.pushToTalk) it.background(Brand.Talking)
                                else if (status.isConnected) it.background(accents.gradient)
                                else it.background(Brand.SurfaceElevated)
                            }
                            .pointerInput(status.isConnected) {
                                detectTapGestures(
                                    onPress = {
                                        if (!status.isConnected) return@detectTapGestures
                                        onPushToTalkDown()
                                        tryAwaitRelease()
                                        onPushToTalkUp()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (status.pushToTalk) stringResource(R.string.call_talking_caps) else stringResource(R.string.call_hold_to_talk),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = if (status.isConnected) Color(0xFF13240F) else Brand.Idle,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.call_ptt_mode),
                        fontSize = 12.sp, color = Brand.TextDim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            // Open call: speaking is enough. The button stays as a smaller override for
            // cutting in, or for when the detector struggles at speed.
            Box(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Comfort.buttonHeight(60.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (status.pushToTalk) Brand.Talking else Brand.SurfaceElevated
                            )
                            .pointerInput(status.isConnected) {
                                detectTapGestures(
                                    onPress = {
                                        if (!status.isConnected) return@detectTapGestures
                                        onPushToTalkDown()
                                        tryAwaitRelease()
                                        onPushToTalkUp()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (status.pushToTalk) stringResource(R.string.call_talking_caps) else stringResource(R.string.call_hold_cut_in),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status.pushToTalk) Color(0xFF13240F) else Brand.TextDim,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.call_open_mode),
                        fontSize = 12.sp, color = Brand.TextDim, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- mute and deafen -----------------------------------------------------
        MuteDeafenRow(status, onToggleMute, onToggleDeafen)

        Spacer(Modifier.height(16.dp))

        // ---- live mic level ------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.call_your_mic),
                        fontSize = 11.sp, letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                    )
                    Spacer(Modifier.width(14.dp))
                    LevelBar(
                        level = micLevel,
                        color = if (status.floor == FloorState.Muted) Brand.Buddy else Brand.Talking,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (status.floor == FloorState.Muted)
                        stringResource(R.string.call_listening_only)
                    else stringResource(R.string.call_speak_normally),
                    fontSize = 12.sp, color = Brand.TextDim,
                )
            }
        }

        if (status.group) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                PeerList(status.peers, onPeerMute, distanceUnit, groupGpsNote(status, rememberDistanceWords()))
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- what the three subsystems are doing ---------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusBar(
                listOf(
                    BarItem(
                        icon = musicIcon,
                        title = stringResource(R.string.common_music),
                        subtitle = if (status.focusHeld) stringResource(R.string.call_music_ducked) else stringResource(R.string.call_music_keep),
                        onClick = onSettings,
                    ),
                    BarItem(
                        icon = MicIcons.Mic,
                        title = stringResource(R.string.common_intercom),
                        subtitle = if (status.isConnected) stringResource(R.string.call_rider_to_rider) else stringResource(R.string.call_offline),
                        active = status.isConnected,
                        onClick = {},
                    ),
                    BarItem(
                        icon = audioIcon,
                        title = stringResource(R.string.common_noise),
                        subtitle = stringResource(noiseSuppression.labelRes()),
                        active = noiseSuppression != NoiseSuppression.Off,
                        onClick = onCycleNoise,
                    ),
                )
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- link detail ---------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.call_link),
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                KeyValueRow(stringResource(R.string.call_state), stringResource(status.link.labelRes()))
                if (!status.group) {
                    KeyValueRow(
                        stringResource(R.string.call_round_trip),
                        if (status.rttMs < 0) stringResource(R.string.call_measuring) else stringResource(R.string.call_rtt_ms, status.rttMs),
                    )
                    KeyValueRow(
                        stringResource(R.string.call_frame_loss),
                        "%.1f %%".format(status.lossPercent),
                        valueColor = if (status.lossPercent > 5) Brand.Buddy else Brand.TextPrimary,
                    )
                    KeyValueRow(stringResource(R.string.call_jitter), stringResource(R.string.call_jitter_frames, status.jitterDepth))
                }
                KeyValueRow(stringResource(R.string.call_role), if (status.isHost) stringResource(R.string.call_role_host) else stringResource(R.string.call_role_joined))
                KeyValueRow(stringResource(R.string.call_time), formatElapsed(status.elapsedSeconds))
            }
        }

        if (status.error != null) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.call_problem), fontSize = 11.sp, letterSpacing = 1.6.sp, color = Brand.Danger)
                    Text(status.error, fontSize = 14.sp, color = Brand.Danger)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        SosControl(status, onSend = onSendSos, onDismissOut = onDismissSosOut)

        Spacer(Modifier.height(14.dp))

        Box(Modifier.padding(horizontal = 16.dp)) {
            Column {
                SecondaryButton(stringResource(R.string.call_end), onEnd, color = Brand.Danger, height = 54.dp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton(stringResource(R.string.common_back), onBack, color = Brand.TextDim, height = 46.dp)
            }
        }
    }
}

/**
 * Says whether the call is protected. Quiet when it is (a small line), loud when it is not:
 * an unencrypted call is something the rider chose in Settings, and must never be mistaken
 * for a normal one.
 */
@Composable
private fun SecurityNotice(status: CallStatus) {
    if (!status.isConnected) return
    when (status.security) {
        ChannelSecurity.Encrypted -> Box(Modifier.padding(start = 20.dp, top = 8.dp, end = 16.dp)) {
            val encryptedDescription = stringResource(R.string.call_is_encrypted)
            Text(
                stringResource(R.string.call_encrypted),
                fontSize = 13.sp, color = Brand.Talking,
                modifier = Modifier.semantics { contentDescription = encryptedDescription },
            )
        }
        ChannelSecurity.Authenticating -> Box(Modifier.padding(start = 20.dp, top = 8.dp, end = 16.dp)) {
            Text(stringResource(R.string.call_checking_code), fontSize = 13.sp, color = Brand.Buddy)
        }
        ChannelSecurity.Unencrypted -> Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.call_not_encrypted),
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Bold, color = Brand.Danger,
                )
                Text(
                    stringResource(R.string.call_not_encrypted_detail),
                    fontSize = 14.sp, color = Brand.Danger, lineHeight = 19.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        ChannelSecurity.Pending -> Unit
    }
}

@Composable
private fun phaseLabel(s: CallStatus): String = when (s.phase) {
    CallPhase.Idle -> stringResource(R.string.phase_idle)
    CallPhase.Starting -> stringResource(R.string.phase_starting)
    CallPhase.Connecting -> if (s.isHost) stringResource(R.string.phase_waiting_buddy) else stringResource(R.string.phase_connecting)
    CallPhase.Active -> stringResource(R.string.phase_connected)
    CallPhase.Reconnecting -> stringResource(R.string.phase_reconnecting)
    CallPhase.Ended -> stringResource(R.string.phase_ended)
    CallPhase.Error -> stringResource(R.string.phase_error)
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
