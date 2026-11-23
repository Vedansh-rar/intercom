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
import com.intercom.app.location.DistanceUnit
import com.intercom.app.group.LinkQuality
import com.intercom.app.location.DistanceText
import com.intercom.app.R
import androidx.compose.ui.res.stringResource

/**
 * The opt-in for convoys. Off by default, so two riders see exactly the screen they always
 * did; on, the Start button begins a group ride and the paired-phones list steps aside.
 */
@Composable
fun GroupRideCard(enabled: Boolean, onChange: (Boolean) -> Unit, internet: Boolean = false) {
    val groupRideDescription = stringResource(R.string.call_group_ride)
    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.group_card_title),
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                Text(
                    if (enabled) stringResource(R.string.group_on) else stringResource(R.string.group_off),
                    fontSize = 15.sp, color = Brand.TextPrimary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = LocalAccents.current.base),
                modifier = Modifier.semantics { contentDescription = groupRideDescription },
            )
        }
        if (enabled) {
            Text(
                stringResource(R.string.group_explain),
                fontSize = 13.sp, color = Brand.TextDim, lineHeight = 18.sp,
            )
            if (internet) {
                Text(
                    stringResource(R.string.group_internet_on),
                    fontSize = 13.sp, color = LocalAccents.current.base, lineHeight = 18.sp,
                )
            }
        }
    }
}

/** The walkie-talkie switch: a push-to-talk group of phones close by, no pairing needed. */
@Composable
fun WalkieCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val description = stringResource(R.string.walkie_title)
    BrandCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.walkie_title),
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                Text(
                    if (enabled) stringResource(R.string.walkie_on) else stringResource(R.string.walkie_off),
                    fontSize = 15.sp, color = Brand.TextPrimary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = LocalAccents.current.base),
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        if (enabled) {
            Text(
                stringResource(R.string.walkie_explain),
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
fun PeerList(
    peers: List<GroupPeer>,
    onMute: (id: Int, muted: Boolean) -> Unit,
    unit: DistanceUnit = DistanceUnit.Kilometres,
    gpsNote: String? = null,
) {
    BrandCard(Modifier.fillMaxWidth()) {
        Text(
            if (peers.isEmpty()) stringResource(R.string.group_riders) else stringResource(R.string.group_riders_n, peers.size),
            fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
        )
        if (peers.isEmpty()) {
            Text(
                stringResource(R.string.group_nobody),
                fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
            )
        }
        peers.forEach { p -> PeerRow(p, onMute, unit) }
        if (gpsNote != null) {
            Text(gpsNote, fontSize = 12.sp, color = Brand.TextDim, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PeerRow(p: GroupPeer, onMute: (Int, Boolean) -> Unit, unit: DistanceUnit) {
    val speaking = p.speaking && !p.muted
    val words = rememberDistanceWords()
    val quality = qualityWord(p.quality)
    val srHost = stringResource(R.string.group_sr_host)
    val srSpeaking = stringResource(R.string.group_sr_speaking)
    val srMuted = stringResource(R.string.group_sr_muted)
    val srLink = stringResource(R.string.group_sr_link, quality)
    val summary = buildString {
        append(p.name)
        if (p.isHost) append(", ").append(srHost)
        if (speaking) append(", ").append(srSpeaking)
        if (p.muted) append(", ").append(srMuted)
        append(", ").append(srLink)
        // The distance reads out in its own sentence for a screen reader.
        p.distance?.let {
            append(", ").append(DistanceText.spoken(null, it, unit, words).removePrefix(words.rider + ", "))
        }
    }
    val muteDescription = if (p.muted) stringResource(R.string.group_unmute_name, p.name)
    else stringResource(R.string.group_mute_name, p.name)
    val muteState = stringResource(if (p.muted) R.string.group_state_muted else R.string.group_state_not_muted)
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
                    Text("  " + stringResource(R.string.group_host_tag), fontSize = 10.sp, letterSpacing = 1.2.sp, color = LocalAccents.current.base)
                }
            }
            Text(
                (if (speaking) stringResource(R.string.group_speaking) + " · " else "") +
                    (if (p.hops <= 1) stringResource(R.string.group_direct)
                    else stringResource(R.string.group_hops, p.hops)) + " · " + quality,
                fontSize = 12.sp,
                color = if (speaking) Brand.Talking else qualityColor(p.quality),
            )
        }
        p.distance?.let { DistanceReadout(name = null, distance = it, unit = unit, compact = true) }
        TextButton(onClick = { onMute(p.id, !p.muted) }) {
            Text(
                if (p.muted) stringResource(R.string.group_unmute) else stringResource(R.string.group_mute),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (p.muted) Brand.Buddy else LocalAccents.current.base,
                modifier = Modifier.semantics {
                    contentDescription = muteDescription
                    stateDescription = muteState
                },
            )
        }
    }
}

@Composable
private fun qualityWord(q: LinkQuality): String = stringResource(
    when (q) {
        LinkQuality.Good -> R.string.q_good
        LinkQuality.Fair -> R.string.q_fair
        LinkQuality.Poor -> R.string.q_poor
        LinkQuality.Unknown -> R.string.q_unknown
    }
)

private fun qualityColor(q: LinkQuality): Color = when (q) {
    LinkQuality.Good -> Brand.TextDim
    LinkQuality.Fair -> Brand.Buddy
    LinkQuality.Poor -> Brand.Danger
    LinkQuality.Unknown -> Brand.TextDim
}
