package com.intercom.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.app.call.CallStatus
import com.intercom.app.call.SosIncoming
import com.intercom.app.location.DistanceFormat
import com.intercom.app.location.DistanceUnit
import com.intercom.app.location.Geo
import com.intercom.app.protocol.PeerInfo
import com.intercom.app.safety.SosTrigger
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Mute and Deafen: two large buttons that show the real state, not the last tap. */
@Composable
fun MuteDeafenRow(
    status: CallStatus,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ControlButton(
            modifier = Modifier.weight(1f),
            label = if (status.micMuted) "UNMUTE" else "MUTE",
            sub = if (status.micMuted) "Mic is off" else "Mic is on",
            on = status.micMuted,
            onColor = Brand.Buddy,
            description = if (status.micMuted) "Unmute microphone" else "Mute microphone",
            onClick = onToggleMute,
        )
        ControlButton(
            modifier = Modifier.weight(1f),
            label = if (status.deafened) "UNDEAFEN" else "DEAFEN",
            sub = if (status.deafened) "You hear nothing" else "Hear and send nothing",
            on = status.deafened,
            onColor = Brand.Danger,
            description = if (status.deafened) "Stop being deafened" else "Deafen: stop hearing and sending",
            onClick = onToggleDeafen,
        )
    }
}

@Composable
private fun ControlButton(
    modifier: Modifier,
    label: String,
    sub: String,
    on: Boolean,
    onColor: Color,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) onColor else Brand.SurfaceElevated)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                color = if (on) Color(0xFF1A1205) else Brand.TextPrimary,
            )
            Text(sub, fontSize = 12.sp, color = if (on) Color(0xFF3B2A06) else Brand.TextDim)
        }
    }
}

/** One line under the status pill when the buddy has muted or deafened themselves. */
@Composable
fun PeerStatusNote(status: CallStatus) {
    val text = when {
        status.group -> return
        status.peerStatus == PeerInfo.Status.Deafened -> "${status.peerName ?: "Your buddy"} is deafened: they cannot hear you"
        status.peerStatus == PeerInfo.Status.Muted -> "${status.peerName ?: "Your buddy"} is muted: you will not hear them"
        else -> return
    }
    Text(
        text, fontSize = 13.sp, color = Brand.Buddy, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * The SOS control. It needs a deliberate act: tap it twice, or hold it for three seconds
 * (a ring fills while held). One tap only arms it, and it lapses by itself.
 */
@Composable
fun SosControl(
    status: CallStatus,
    onSend: () -> Unit,
    onDismissOut: () -> Unit,
) {
    val trigger = remember { SosTrigger() }
    var armed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val enabled = status.sosAvailable
    val out = status.sosOut

    LaunchedEffect(enabled) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            when (trigger.tick(now)) {
                SosTrigger.Outcome.Fire -> onSend()
                else -> Unit
            }
            armed = trigger.armed
            progress = trigger.holdProgress(now)
            delay(50)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (out != null) {
            SosOutStatus(out, onDismissOut)
            Spacer(Modifier.height(10.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        !enabled -> Brand.SurfaceElevated
                        armed || progress > 0f -> Brand.Danger
                        else -> Color(0xFF3A1414)
                    }
                )
                .semantics {
                    contentDescription = "SOS. Tap twice, or hold for three seconds, to send an alert to your buddy"
                }
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled) return@detectTapGestures
                            trigger.press(SystemClock.elapsedRealtime())
                            tryAwaitRelease()
                            val outcome = trigger.release(SystemClock.elapsedRealtime())
                            armed = trigger.armed
                            progress = 0f
                            if (outcome == SosTrigger.Outcome.Fire) onSend()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(Color(0x55FFFFFF))
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        !enabled -> "SOS"
                        progress > 0f -> "KEEP HOLDING  ${(progress * 100).roundToInt()}%"
                        armed -> "TAP AGAIN TO SEND"
                        else -> "SOS"
                    },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    color = if (enabled) Color.White else Brand.Idle,
                )
                Text(
                    when {
                        !enabled -> "Available once you are linked to a buddy or group"
                        armed -> "Or hold for 3 seconds"
                        else -> "Tap twice, or hold 3 seconds"
                    },
                    fontSize = 12.sp,
                    color = if (enabled) Color(0xFFFFD6D6) else Brand.Idle,
                )
            }
        }
    }
}

@Composable
private fun SosOutStatus(out: com.intercom.app.call.SosOutgoing, onDismiss: () -> Unit) {
    val (text, color) = when {
        out.failed -> "SOS was not sent: no link for two minutes." to Brand.Danger
        !out.sent -> "SOS is waiting for the link, and will go the moment it is back." to Brand.Buddy
        out.withPosition -> "SOS sent with your last known position. It is repeated twice." to Brand.Talking
        else -> "SOS sent without a position (location sharing is off). It is repeated twice." to Brand.Talking
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceElevated).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
        Text(
            if (out.sent || out.failed) "OK" else "CANCEL",
            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Brand.TextPrimary,
            modifier = Modifier.padding(start = 12.dp).clickable(role = Role.Button, onClick = onDismiss).padding(8.dp),
        )
    }
}

/** The full-screen banner for a received SOS. It stays until dismissed. */
@Composable
fun SosBanner(sos: SosIncoming, unit: DistanceUnit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFB91C1C))
            .safeDrawingPadding()
            .semantics { contentDescription = "S O S alert from ${sos.from}" },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("SOS", fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "${sos.from} needs help",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (sos.latDeg != null && sos.lonDeg != null) {
                if (sos.meters != null && sos.bearingDeg != null) {
                    Text(
                        "${DistanceFormat.format(sos.meters, unit).text} ${Geo.cardinal(sos.bearingDeg)} of you",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "%.5f, %.5f".format(sos.latDeg, sos.lonDeg),
                    fontSize = 22.sp, color = Color.White, textAlign = TextAlign.Center,
                )
                val age = sos.positionAgeSec
                if (age != null && age >= 30) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            age >= 630 -> "Position is over 10 minutes old"
                            age < 60 -> "Position is about $age seconds old"
                            else -> "Position is about ${(age / 60.0).roundToInt()} min old"
                        },
                        fontSize = 15.sp, color = Color(0xFFFFE4E4), textAlign = TextAlign.Center,
                    )
                }
                if (sos.meters == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Switch on \"Share my location\" to see how far away it is.",
                        fontSize = 14.sp, color = Color(0xFFFFE4E4), textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    "No position was sent. Call or message them.",
                    fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Sent at ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(sos.atMs))} (their clock)",
                fontSize = 13.sp, color = Color(0xFFFFE4E4),
            )
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("DISMISS", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7F1D1D), letterSpacing = 2.sp)
            }
        }
    }
}
