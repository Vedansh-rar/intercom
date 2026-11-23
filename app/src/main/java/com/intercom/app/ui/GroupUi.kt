package com.intercom.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.app.group.GroupPeer
import com.intercom.app.group.LinkQuality

/**
 * The opt-in for convoys. Off by default, so two riders see exactly the screen they always
 * did; on, the Start button begins a group ride and the paired-phones list steps aside.
 */
@Composable
fun GroupRideCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "GROUP RIDE",
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                Text(
                    if (enabled) "On: three or more riders" else "Off: two riders, as usual",
                    fontSize = 15.sp, color = Brand.TextPrimary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = LocalAccents.current.base),
                modifier = Modifier.semantics { contentDescription = "Group ride" },
            )
        }
        if (enabled) {
            Text(
                "Everyone uses the same ride code. The host starts the ride and the others " +
                    "join it; no pairing needed. Needs Google Play services. Riders further " +
                    "apart than one phone's range are reached through the riders between them.",
                fontSize = 13.sp, color = Brand.TextDim, lineHeight = 18.sp,
            )
        }
    }
}

/**
 * Everyone on the ride: who is speaking, how good their link is, and a mute for each.
 *
 * Mute is for this phone only. It silences that rider in your helmet; they can still talk to
 * everyone else, and your phone still passes their voice along to riders further back.
 */
@Composable
fun PeerList(peers: List<GroupPeer>, onMute: (id: Int, muted: Boolean) -> Unit) {
    BrandCard(Modifier.fillMaxWidth()) {
        Text(
            if (peers.isEmpty()) "RIDERS" else "RIDERS  ·  ${peers.size}",
            fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
        )
        if (peers.isEmpty()) {
            Text(
                "Nobody has joined yet. Riders appear here as they connect.",
                fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
            )
        }
        peers.forEach { p -> PeerRow(p, onMute) }
    }
}

@Composable
private fun PeerRow(p: GroupPeer, onMute: (Int, Boolean) -> Unit) {
    val speaking = p.speaking && !p.muted
    val summary = buildString {
        append(p.name)
        if (p.isHost) append(", host")
        if (speaking) append(", speaking")
        if (p.muted) append(", muted")
        append(", link ").append(qualityWord(p.quality))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = summary },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Colour is never the only signal: the word "speaking" is on screen as well.
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (speaking) Brand.Talking else Brand.Border),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (p.muted) Brand.TextDim else Brand.TextPrimary,
                )
                if (p.isHost) {
                    Text("  HOST", fontSize = 10.sp, letterSpacing = 1.2.sp, color = LocalAccents.current.base)
                }
            }
            Text(
                (if (speaking) "Speaking · " else "") +
                    (if (p.hops <= 1) "Direct" else "${p.hops} hops") + " · " + qualityWord(p.quality),
                fontSize = 12.sp,
                color = if (speaking) Brand.Talking else qualityColor(p.quality),
            )
        }
        TextButton(onClick = { onMute(p.id, !p.muted) }) {
            Text(
                if (p.muted) "Unmute" else "Mute",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (p.muted) Brand.Buddy else LocalAccents.current.base,
                modifier = Modifier.semantics {
                    contentDescription = if (p.muted) "Unmute ${p.name}" else "Mute ${p.name}"
                    stateDescription = if (p.muted) "Muted" else "Not muted"
                },
            )
        }
    }
}

private fun qualityWord(q: LinkQuality) = when (q) {
    LinkQuality.Good -> "good"
    LinkQuality.Fair -> "fair"
    LinkQuality.Poor -> "poor"
    LinkQuality.Unknown -> "unknown"
}

private fun qualityColor(q: LinkQuality): Color = when (q) {
    LinkQuality.Good -> Brand.TextDim
    LinkQuality.Fair -> Brand.Buddy
    LinkQuality.Poor -> Brand.Danger
    LinkQuality.Unknown -> Brand.TextDim
}
