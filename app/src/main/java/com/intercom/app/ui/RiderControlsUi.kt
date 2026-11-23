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
import com.intercom.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn

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
            label = if (status.micMuted) stringResource(R.string.rc_unmute) else stringResource(R.string.rc_mute),
            sub = if (status.micMuted) stringResource(R.string.rc_mic_off) else stringResource(R.string.rc_mic_on),
            on = status.micMuted,
            onColor = Brand.Buddy,
            description = if (status.micMuted) stringResource(R.string.rc_unmute_desc) else stringResource(R.string.rc_mute_desc),
            onClick = onToggleMute,
        )
        ControlButton(
            modifier = Modifier.weight(1f),
            label = if (status.deafened) stringResource(R.string.rc_undeafen) else stringResource(R.string.rc_deafen),
            sub = if (status.deafened) stringResource(R.string.rc_hear_nothing) else stringResource(R.string.rc_hear_send_nothing),
            on = status.deafened,
            onColor = Brand.Danger,
            description = if (status.deafened) stringResource(R.string.rc_undeafen_desc) else stringResource(R.string.rc_deafen_desc),
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
            .heightIn(min = Comfort.buttonHeight(76.dp))
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
    if (status.group) return
    val who = status.peerName ?: stringResource(R.string.peer_your_buddy)
    val text = when (status.peerStatus) {
        PeerInfo.Status.Deafened -> stringResource(R.string.peer_deafened, who)
        PeerInfo.Status.Muted -> stringResource(R.string.peer_muted, who)
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
    val sosDescription = stringResource(R.string.sos_description)

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
                .heightIn(min = Comfort.buttonHeight(88.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        !enabled -> Brand.SurfaceElevated
                        armed || progress > 0f -> Brand.Danger
                        else -> Color(0xFF3A1414)
                    }
                )
                .semantics { contentDescription = sosDescription }
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
                        !enabled -> stringResource(R.string.sos_label)
                        progress > 0f -> stringResource(R.string.sos_keep_holding, (progress * 100).roundToInt())
                        armed -> stringResource(R.string.sos_tap_again)
                        else -> stringResource(R.string.sos_label)
                    },
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                    color = if (enabled) Color.White else Brand.Idle,
                )
                Text(
                    when {
                        !enabled -> stringResource(R.string.sos_unavailable)
                        armed -> stringResource(R.string.sos_or_hold)
                        else -> stringResource(R.string.sos_hint)
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
        out.failed -> stringResource(R.string.sos_not_sent) to Brand.Danger
        !out.sent -> stringResource(R.string.sos_waiting) to Brand.Buddy
        out.withPosition -> stringResource(R.string.sos_sent_pos) to Brand.Talking
        else -> stringResource(R.string.sos_sent_nopos) to Brand.Talking
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceElevated).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, color = color, modifier = Modifier.weight(1f))
        Box(
            Modifier.padding(start = 12.dp).heightIn(min = Comfort.MIN_TOUCH_DP.dp).widthIn(min = Comfort.MIN_TOUCH_DP.dp)
                .clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (out.sent || out.failed) stringResource(R.string.common_ok) else stringResource(R.string.common_cancel_caps),
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Brand.TextPrimary,
            )
        }
    }
}

/** The full-screen banner for a received SOS. It stays until dismissed. */
@Composable
fun SosBanner(sos: SosIncoming, unit: DistanceUnit, onDismiss: () -> Unit) {
    val bannerDescription = stringResource(R.string.sos_banner_description, sos.from)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFB91C1C))
            .safeDrawingPadding()
            .semantics { contentDescription = bannerDescription },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.sos_label), fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sos_needs_help, sos.from),
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (sos.latDeg != null && sos.lonDeg != null) {
                if (sos.meters != null && sos.bearingDeg != null) {
                    val distText = DistanceFormat.format(sos.meters, unit).text
                    val dirText = Geo.cardinal(sos.bearingDeg)
                    Text(
                        stringResource(R.string.sos_distance_dir, distText, dirText),
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
                            age >= 630 -> stringResource(R.string.sos_pos_old10)
                            age < 60 -> stringResource(R.string.sos_pos_old_s, age)
                            else -> stringResource(R.string.sos_pos_old_m, (age / 60.0).roundToInt())
                        },
                        fontSize = 15.sp, color = Color(0xFFFFE4E4), textAlign = TextAlign.Center,
                    )
                }
                if (sos.meters == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.sos_enable_sharing),
                        fontSize = 14.sp, color = Color(0xFFFFE4E4), textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.sos_no_position),
                    fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            val sentAt = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(sos.atMs))
            Text(
                stringResource(R.string.sos_sent_at, sentAt),
                fontSize = 13.sp, color = Color(0xFFFFE4E4),
            )
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Comfort.buttonHeight(76.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.sos_dismiss), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7F1D1D), letterSpacing = 2.sp)
            }
        }
    }
}
