package com.intercom.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.app.call.CallStatus
import com.intercom.app.location.DistanceText
import com.intercom.app.location.DistanceUnit
import com.intercom.app.location.LocationState
import com.intercom.app.location.RiderDistance

/**
 * An arrow that points where the other rider is, relative to the way this rider is heading:
 * straight up means dead ahead, down means behind. [relativeDeg] is degrees clockwise from
 * ahead (negative is to the left). With no heading there is nothing to point relative to,
 * so the caller shows a compass word instead of drawing this.
 */
@Composable
fun DirectionArrow(relativeDeg: Double, color: Color, size: Dp = 32.dp) {
    Canvas(Modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.width
        val h = this.size.height
        val centre = Offset(w / 2f, h / 2f)
        rotate(degrees = relativeDeg.toFloat(), pivot = centre) {
            val arrow = Path().apply {
                moveTo(w * 0.5f, h * 0.06f)     // tip
                lineTo(w * 0.86f, h * 0.90f)    // right wing
                lineTo(w * 0.5f, h * 0.70f)     // notch
                lineTo(w * 0.14f, h * 0.90f)    // left wing
                close()
            }
            drawPath(arrow, color)
        }
    }
}

/**
 * How far away the other rider is: a big number, an arrow, and a line saying "ahead" or
 * "last seen 12 s ago". The number is greyed once their position is stale, so an old
 * figure is never mistaken for a live one; colour is never the only cue, the words are there too.
 */
@Composable
fun DistanceReadout(
    name: String?,
    distance: RiderDistance,
    unit: DistanceUnit,
    compact: Boolean = false,
) {
    val stale = distance.stale
    val tint = if (stale) Brand.TextDim else Brand.TextPrimary
    val accent = if (stale) Brand.Idle else LocalAccents.current.base
    val detail = DistanceText.detail(distance)
    val rel = distance.relativeDeg

    Row(
        // In a peer row the row itself already speaks the distance; do not say it twice.
        if (compact) Modifier.clearAndSetSemantics { }
        else Modifier.semantics(mergeDescendants = true) {
            contentDescription = DistanceText.spoken(name, distance, unit)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        if (rel != null) {
            DirectionArrow(rel, accent, if (compact) 22.dp else 40.dp)
        }
        Column {
            Text(
                DistanceText.distance(distance, unit),
                fontSize = if (compact) 15.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
            if (detail != null) {
                Text(detail, fontSize = if (compact) 11.sp else 13.sp, color = Brand.TextDim)
            }
        }
    }
}

/** The buddy's distance on a two-rider call, with whatever the GPS needs to say. */
@Composable
fun BuddyDistanceCard(status: CallStatus, unit: DistanceUnit) {
    val d = status.buddy
    val note = DistanceText.gpsNote(status.location, d != null, d?.ownStale == true)
    BrandCard(Modifier.fillMaxWidth()) {
        Text(
            "DISTANCE",
            fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
        )
        if (d != null) DistanceReadout(status.peerName ?: "Buddy", d, unit)
        if (note != null) {
            Text(note, fontSize = 13.sp, color = Brand.TextDim, lineHeight = 18.sp)
        }
    }
}

/** A short, loud banner while the "falling behind" alert is active. */
@Composable
fun FallingBehindBanner(who: String?) {
    BrandCard(Modifier.fillMaxWidth()) {
        Text(
            "FALLING BEHIND",
            fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Bold, color = Brand.Danger,
        )
        Text(
            if (who != null) "$who is falling behind" else "A rider is falling behind",
            fontSize = 16.sp, color = Brand.TextPrimary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

/** The GPS line for a group ride, where there is no single distance card. Null when none is needed. */
fun groupGpsNote(status: CallStatus): String? {
    val distances = status.peers.mapNotNull { it.distance }
    return DistanceText.gpsNote(status.location, distances.isNotEmpty(), distances.any { it.ownStale })
}

/** Who is furthest behind among riders whose position is current, for the alert banner. */
fun farthestRiderName(status: CallStatus): String? =
    if (status.group) {
        status.peers
            .filter { it.distance?.stale == false }
            .maxByOrNull { it.distance?.meters ?: 0.0 }
            ?.name
    } else status.peerName

/** Whether to show the distance UI at all: only while the rider shares. */
fun showsLocation(status: CallStatus): Boolean = status.location != LocationState.Off
