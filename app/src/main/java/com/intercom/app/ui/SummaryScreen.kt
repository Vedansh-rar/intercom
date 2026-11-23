package com.intercom.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.app.R
import com.intercom.app.summary.RideSummary
import com.intercom.app.summary.SummaryText
import com.intercom.app.transport.Transport

/**
 * Shown after a call ends: how long it ran, how the link held up, and where the phones
 * changed radio. Built from memory only; closing the screen discards it.
 */
@Composable
fun SummaryScreen(summary: RideSummary, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.sum_title),
            subtitle = stringResource(R.string.sum_subtitle),
            onBack = onDone,
        )

        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(stringResource(R.string.sum_duration), SummaryText.clock(summary.durationMs), Modifier.weight(1f))
                BigStat(
                    stringResource(R.string.sum_connected),
                    stringResource(R.string.sum_percent, summary.connectedPercent),
                    Modifier.weight(1f),
                    color = if (summary.connectedPercent >= 90) Brand.Talking else Brand.Buddy,
                )
            }
            KeyValueRow(stringResource(R.string.sum_riders), summary.riders.toString())
            KeyValueRow(stringResource(R.string.sum_drops), summary.linkLosses.toString())
            summary.avgRttMs?.let { KeyValueRow(stringResource(R.string.sum_avg_delay), stringResource(R.string.call_rtt_ms, it)) }
            summary.worstRttMs?.let { KeyValueRow(stringResource(R.string.sum_worst_delay), stringResource(R.string.call_rtt_ms, it)) }
            KeyValueRow(
                stringResource(R.string.sum_worst_loss),
                stringResource(R.string.sum_percent, "%.0f".format(summary.worstLossPercent)),
            )
        }

        SectionCard(title = stringResource(R.string.sum_graph_title)) {
            QualityGraph(summary)
        }

        SectionCard(title = stringResource(R.string.sum_switches_title)) {
            if (summary.switches.isEmpty()) {
                Text(
                    stringResource(R.string.sum_no_switches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
            } else {
                summary.switches.forEach { sw ->
                    Text(
                        stringResource(
                            R.string.sum_switch_line,
                            SummaryText.clock(sw.atMs),
                            radioName(sw.from),
                            radioName(sw.to),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        Text(
            stringResource(R.string.sum_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = IntercomColors.dim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            PrimaryButton(stringResource(R.string.sum_done), true, onDone)
        }
    }
}

/** The recorder stores English labels; show the rider's language. */
@Composable
private fun radioName(label: String?): String {
    val t = Transport.entries.firstOrNull { it.label == label }
    return if (t != null) stringResource(t.labelRes()) else stringResource(R.string.sum_unknown_radio)
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Brand.TextPrimary) {
    Column(modifier) {
        Text(value, fontSize = 30.sp, color = color, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = IntercomColors.dim)
    }
}

/**
 * Link quality through the ride: full height is a clean link, the floor is a dropped one.
 * Dropped stretches are marked in red and every radio switch has a vertical line.
 */
@Composable
private fun QualityGraph(summary: RideSummary) {
    val description = stringResource(
        R.string.sum_graph_desc, summary.connectedPercent, summary.linkLosses, summary.switches.size,
    )
    val line = Brand.Talking
    val warn = Brand.Buddy
    val down = Brand.Danger
    val grid = Brand.Border
    val mark = Brand.TextDim
    val points = SummaryText.graph(summary)
    val span = summary.durationMs.coerceAtLeast(1).toFloat()

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description },
        ) {
            val w = size.width
            val h = size.height
            fun x(f: Float) = f * w
            fun y(q: Float) = h - q * h
            // gridlines at top, middle and bottom
            for (f in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(0f, y(f)), Offset(w, y(f)), strokeWidth = 1.dp.toPx())

            // switches
            summary.switches.forEach { sw ->
                val sx = x((sw.atMs / span).coerceIn(0f, 1f))
                drawLine(mark, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.5.dp.toPx())
            }

            val fill = Path().apply {
                moveTo(x(points.first().first), h)
                points.forEach { lineTo(x(it.first), y(it.second)) }
                lineTo(x(points.last().first), h)
                close()
            }
            drawPath(fill, line.copy(alpha = 0.18f))

            val stroke = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.first), y(p.second)) else lineTo(x(p.first), y(p.second)) }
            }
            drawPath(stroke, line, style = Stroke(width = 2.5.dp.toPx()))

            // dropped samples
            summary.samples.filter { !it.connected }.forEach {
                val sx = x((it.atMs / span).coerceIn(0f, 1f))
                drawCircle(down, radius = 4.dp.toPx(), center = Offset(sx, y(0f)))
            }
            // poor-quality samples, so a "connected but bad" stretch does not look healthy
            summary.samples.filter { it.connected && it.quality < 0.5f }.forEach {
                val sx = x((it.atMs / span).coerceIn(0f, 1f))
                drawCircle(warn, radius = 3.dp.toPx(), center = Offset(sx, y(it.quality)))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0:00", fontSize = 11.sp, color = IntercomColors.dim)
            Text(stringResource(R.string.sum_graph_top) + " ↑  " + stringResource(R.string.sum_graph_bottom) + " ↓", fontSize = 11.sp, color = IntercomColors.dim)
            Text(SummaryText.clock(summary.durationMs), fontSize = 11.sp, color = IntercomColors.dim)
        }
    }
}
