package com.opentasker.ui.charts.compare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.ChartTicks
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.compare.CompareMarks.drawDeviceKey
import com.opentasker.ui.charts.compare.CompareMarks.drawPairRail
import com.opentasker.ui.charts.render.PlotFrame
import com.opentasker.ui.charts.render.drawGrid
import com.opentasker.ui.charts.render.drawTimeLabels
import com.opentasker.ui.charts.render.drawValueLabels
import com.opentasker.ui.charts.render.drawPoints
import kotlin.math.abs
import kotlin.math.max

/**
 * One metric, two bands, stacked.
 *
 * ## Why stacked and not overlaid
 *
 * The obvious design is one plot with two series on it, and it fails for a concrete reason: gap
 * tinting fills the whole plot rectangle, so two devices' gaps on one rectangle either merge into a
 * wash or need striping to tell apart. Two rectangles give two tints and no ambiguity — and, more
 * importantly, make it structurally impossible to read the two bands as one series, which is the
 * misreading this entire screen exists to prevent.
 *
 * ## The device is said four times, never once in colour
 *
 * 白い熊 is red-green colour-blind, and there is no palette of ten separable hues to spend one on a
 * device. So hue stays with the METRIC — both tracks are drawn in the metric's own colour — and the
 * band is carried by **track position**, **mark fill** (Band 11 solid, Hume hollow), the **row
 * label**, and the **rail tick direction**. All four survive greyscale.
 *
 * Band 11 goes on top deliberately: it is the band that will remain, so the layout already reads as
 * the demotion it is heading towards.
 *
 * ## One scale, stated
 *
 * Both tracks share a single y range, computed across both devices. A stacked comparison is
 * worthless if the two halves are drawn against different numbers, and a reader cannot check that by
 * looking — so the footer says the range outright.
 */
@Composable
fun CompareCard(
    title: String,
    unit: String,
    color: Color,
    join: CompareData.Join,
    viewport: ChartViewport,
    footer: List<String>,
    tier: CompareTier,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { "%.0f".format(it) },
) {
    val style = LocalChartStyle.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    // One range across BOTH devices. Taking each track's own range would let a 60-beat Hume track and
    // a 160-beat Huawei track occupy identical heights, which is the most convincing wrong chart
    // this screen could draw.
    val bounds = remember(join) {
        val values = join.cells.flatMap { listOfNotNull(it.huawei, it.hume) }
        if (values.isEmpty()) 0.0 to 1.0
        else {
            val lo = values.min()
            val hi = values.max()
            val pad = ((hi - lo) * 0.08).takeIf { it > 0.0 } ?: max(abs(hi) * 0.08, 1.0)
            (lo - pad) to (hi + pad)
        }
    }
    val railScale = remember(join) {
        join.cells.mapNotNull { it.delta }.maxOfOrNull { abs(it) }?.takeIf { it > 0.0 } ?: 1.0
    }

    SectionCard(accent = color) {
        SectionTitle(title, color)
        Row(modifier = Modifier.fillMaxWidth()) {
            tier.Chip()
        }

        Canvas(
            modifier
                .fillMaxWidth()
                .height(196.dp)
                .padding(top = 6.dp),
        ) {
            // Three bands of one canvas: the two tracks take most of it, the rail a slim strip.
            // PlotFrame already takes its rect as a parameter, so this needs no new drawing
            // machinery — only three rects and one shared viewport.
            // A strip at the foot for the time labels. drawTimeLabels writes BELOW its frame's
            // rect, so without reserving this the axis lands inside the rail and the two read as
            // one smear.
            val labelStrip = 24f
            val usable = size.height - labelStrip
            val railHeight = usable * 0.18f
            val trackHeight = (usable - railHeight) / 2f
            val left = 0f
            val right = size.width

            val upper = PlotFrame(
                Rect(left, 0f, right, trackHeight), viewport, bounds.first, bounds.second, style,
            )
            val lower = PlotFrame(
                Rect(left, trackHeight, right, trackHeight * 2f), viewport,
                bounds.first, bounds.second, style,
            )
            val rail = PlotFrame(
                Rect(left, trackHeight * 2f, right, trackHeight * 2f + railHeight), viewport,
                bounds.first, bounds.second, style,
            )

            // One tick set for both tracks and the rail: a shared time axis is the point, and
            // computing it twice invites the two halves to disagree by a pixel.
            val ticks = ChartTicks.labelled(
                ChartTicks.forSpan(viewport.startMs, viewport.endMs, java.time.ZoneId.systemDefault()),
            )
            drawGrid(upper, ticks)
            drawGrid(lower, ticks)
            // Time labels once, and beneath the RAIL rather than between the tracks — there is one
            // axis for all three bands, and putting it in the middle would suggest two charts.
            drawTimeLabels(rail, ticks, measurer)
            // Value labels ONCE, on the upper track. Drawing them on both collided at the boundary
            // — the upper track's floor and the lower track's ceiling are the same number in the
            // same place — and labelling a shared scale twice invites the reader to check whether
            // the two sets agree, which is exactly the doubt the single scale exists to remove.
            drawValueLabels(upper, measurer, { format(it) })

            // Band 11 above, filled. Hume below, hollow. Same colour on purpose: the hue is the
            // metric, and spending it on the device would leave the metric unidentifiable.
            drawPoints(
                upper,
                join.cells.mapNotNull { c -> c.huawei?.let { ChartPoint(c.epochMs, it) } },
                color,
                hollow = false,
            )
            drawPoints(
                lower,
                join.cells.mapNotNull { c -> c.hume?.let { ChartPoint(c.epochMs, it) } },
                color,
                hollow = true,
            )

            drawPairRail(
                rail,
                CompareMarks.columns(join, rail),
                railScale,
                color,
                style.axisText,
            )

            // The two keys sit on their own tracks rather than in a shared legend, so the mark and
            // the track it belongs to are never more than a few pixels apart.
            drawDeviceKey(Offset(left + 8f, 10f), 4f, color, hollow = false)
            drawDeviceKey(Offset(left + 8f, trackHeight + 10f), 4f, color, hollow = true)
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                "● Band 11    ○ Hume",
                style = MaterialTheme.typography.bodySmall,
                color = style.axisText,
            )
        }
        for (line in footer) NoteText(line)
    }
}

/**
 * A card for a comparison that was refused.
 *
 * Shown where the reader looks for the comparison, naming the metric and the reason — because the
 * alternative is a chart that is quietly wrong, and a reader who never learns the question was
 * refused will assume it was answered.
 */
@Composable
fun CompareRefusedCard(title: String, refusal: CompareData.Refusal, color: Color) {
    SectionCard(accent = color) {
        SectionTitle(title, color)
        CompareTier.REFUSED.Chip()
        BodyText(refusal.detail)
    }
}
