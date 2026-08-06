package com.opentasker.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.render.PlotFrame
import com.opentasker.ui.charts.render.drawAxisBreak
import com.opentasker.ui.charts.render.drawBars
import com.opentasker.ui.charts.render.drawCapsules
import com.opentasker.ui.charts.render.drawDumbbells
import com.opentasker.ui.charts.render.drawGaps
import com.opentasker.ui.charts.render.drawGrid
import com.opentasker.ui.charts.render.drawHypnogram
import com.opentasker.ui.charts.render.drawLineSeries
import com.opentasker.ui.charts.render.drawRejected
import com.opentasker.ui.charts.render.drawTimeLabels
import com.opentasker.ui.charts.render.drawValueLabels
import java.time.ZoneId

/**
 * The plots: data in, pixels out.
 *
 * Every one of these reads the viewport **inside the draw lambda**, never in the composable body.
 * That is what makes a pinch redraw without recomposing the subtree — reading viewport state in the
 * body turns a 2 ms frame into a 20 ms one, because the whole column recomposes on every gesture
 * event.
 */

private const val AXIS_RIGHT = 34f
private const val AXIS_BOTTOM = 18f

@Composable
fun MetricPlot(
    chart: MetricChart,
    viewport: ChartViewport,
    modifier: Modifier = Modifier,
    showAxes: Boolean = true,
    /**
     * The ✕ marks at flagged samples' real values.
     *
     * On by default in the detail view and OFF in the previews. They are the proof of what the
     * filter dropped and they have to stay reachable — but a hundred of them across a 132 dp preview
     * is noise that hides the shape the card exists to show.
     */
    showRejected: Boolean = showAxes,
) {
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val spec = chart.spec

    Canvas(modifier.fillMaxWidth()) {
        val rect = Rect(
            left = 0f,
            top = 4f,
            right = size.width - if (showAxes) AXIS_RIGHT else 0f,
            bottom = size.height - if (showAxes) AXIS_BOTTOM else 0f,
        )
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        viewport.plotWidthPx = rect.width

        // The axis holds its clinical band but expands to fit anything outside it, so a real
        // excursion is never clipped off the top of the plot.
        val dataLo = chart.buckets.minOfOrNull { it.lo }
            ?: chart.chunk?.segments?.flatMap { it.points }?.minOfOrNull { it.value }
            ?: chart.bars.minOfOrNull { it.value }
        val dataHi = chart.buckets.maxOfOrNull { it.hi }
            ?: chart.chunk?.segments?.flatMap { it.points }?.maxOfOrNull { it.value }
            ?: chart.bars.maxOfOrNull { it.value }
        val yMin = minOf(spec.yMin, dataLo ?: spec.yMin)
        val yMax = maxOf(spec.yMax, dataHi ?: spec.yMax)
        val frame = PlotFrame(rect, viewport, yMin, yMax)

        val ticks = ChartTicks.labelled(ChartTicks.forSpan(viewport.startMs, viewport.endMs, zone))
        drawGrid(frame, ticks)
        chart.chunk?.let { drawGaps(frame, it.gaps) }

        when (spec.render) {
            RenderKind.CAPSULE -> drawCapsules(frame, chart.buckets, spec.color)
            RenderKind.BARS -> drawBars(frame, chart.bars, 60_000L, spec.color)
            else -> chart.chunk?.let {
                val segments = ChartPipeline.render(it, viewport.spanMs, rect.width).segments
                drawLineSeries(frame, segments, spec.color, showDots = viewport.spanMs < 12 * 3_600_000L)
            }
        }
        // The failed-read population, drawn as separate dots rather than joined to the line. They are
        // a different record type, not a continuation of the same series, and a line through both is
        // what made this look like one metric with a wide healthy-looking range.
        for (p in chart.secondary) {
            if (!frame.visible(p.tMs)) continue
            drawCircle(
                ChartPalette.BAND_WARN,
                radius = 3f,
                center = androidx.compose.ui.geometry.Offset(frame.x(p.tMs), frame.y(p.value)),
            )
        }
        if (showRejected) chart.chunk?.let { drawRejected(frame, it.rejectedPoints) }

        if (showAxes) {
            drawTimeLabels(frame, ticks, measurer)
            drawValueLabels(frame, measurer, format = { v -> spec.format(v) })
            if (spec.axisBreak) drawAxisBreak(frame)
        }
    }
}

@Composable
fun BloodPressurePlot(
    chart: BloodPressureChart,
    viewport: ChartViewport,
    modifier: Modifier = Modifier,
    showAxes: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }

    Canvas(modifier.fillMaxWidth()) {
        val rect = Rect(0f, 4f, size.width - if (showAxes) AXIS_RIGHT else 0f, size.height - if (showAxes) AXIS_BOTTOM else 0f)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        viewport.plotWidthPx = rect.width

        val lo = chart.dumbbells.mapNotNull { d -> d.lower?.lo ?: d.upper?.lo }.minOrNull() ?: 50.0
        val hi = chart.dumbbells.mapNotNull { d -> d.upper?.hi ?: d.lower?.hi }.maxOrNull() ?: 160.0
        // ONE axis for both series — they share a unit, so they share a scale. Two y-scales would let
        // any two series be made to look correlated by choosing them.
        val frame = PlotFrame(rect, viewport, minOf(50.0, lo - 5), maxOf(160.0, hi + 5))

        val ticks = ChartTicks.labelled(ChartTicks.forSpan(viewport.startMs, viewport.endMs, zone))
        drawGrid(frame, ticks)
        drawDumbbells(frame, chart.dumbbells, ChartPalette.SYSTOLIC, ChartPalette.DIASTOLIC)
        if (showAxes) {
            drawTimeLabels(frame, ticks, measurer)
            drawValueLabels(frame, measurer, format = { v -> v.toInt().toString() })
        }
    }
}

@Composable
fun SleepPlot(
    chart: SleepChart,
    viewport: ChartViewport,
    modifier: Modifier = Modifier,
    showAxes: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val zone = remember { ZoneId.systemDefault() }
    val runs = remember(chart) { chart.sessions.flatMap { it.runs } }

    Canvas(modifier.fillMaxWidth()) {
        val rect = Rect(0f, 4f, size.width - if (showAxes) AXIS_RIGHT else 0f, size.height - if (showAxes) AXIS_BOTTOM else 0f)
        if (rect.width <= 0f || rect.height <= 0f) return@Canvas
        viewport.plotWidthPx = rect.width
        val frame = PlotFrame(rect, viewport, 0.0, 1.0)

        val ticks = ChartTicks.labelled(ChartTicks.forSpan(viewport.startMs, viewport.endMs, zone))
        drawGrid(frame, ticks, horizontalLines = SleepShape.ROWS.size)
        drawHypnogram(
            frame = frame,
            runs = runs,
            rowOf = SleepShape::rowOf,
            rows = SleepShape.ROWS.size,
            colorOf = ChartPalette::sleepStage,
        )
        if (showAxes) drawTimeLabels(frame, ticks, measurer)
    }
}

/** Stage identity carried by a label beside its colour, never by the colour alone. */
@Composable
fun SleepLegend(modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SleepShape.ROWS.forEach { code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartPalette.sleepStage(code)))
                Spacer(Modifier.width(5.dp))
                Text(
                    SleepShape.labelOf(code)[LocalBandLanguage.current],
                    style = MaterialTheme.typography.labelSmall,
                    color = ChartPalette.AXIS_TEXT,
                )
            }
        }
    }
}

@Composable
fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ChartPalette.AXIS_TEXT)
    }
}

// --- preview cards ---------------------------------------------------------------------------

private val PREVIEW_HEIGHT = 132.dp

@Composable
fun MetricPreviewCard(chart: MetricChart, bounds: LongRange, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val viewport = rememberPreviewViewport(bounds)
    ChartCard(
        title = chart.spec.label[lang],
        headline = chart.headline,
        unit = chart.spec.unit,
        band = chart.headlineBand,
        subtitle = chart.subtitle[lang],
        accent = chart.spec.color,
        onClick = onClick,
    ) {
        if (chart.isEmpty) {
            EmptyPlot()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricPlot(chart, viewport, Modifier.height(PREVIEW_HEIGHT), showAxes = false)
                if (chart.secondary.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LegendEntry(chart.spec.color, BandText.readOk[lang])
                        LegendEntry(ChartPalette.BAND_WARN, BandText.readFailed[lang])
                    }
                }
            }
        }
    }
}

@Composable
fun BloodPressurePreviewCard(chart: BloodPressureChart, bounds: LongRange, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val viewport = rememberPreviewViewport(bounds)
    ChartCard(
        title = BandText.bloodPressure[lang],
        headline = chart.headline,
        unit = "mmHg",
        band = BandRung(BandText.notAMeasurement, Double.MAX_VALUE, ChartPalette.BAND_WARN),
        subtitle = BandText.bpRanges[lang].format(chart.systolicRange, chart.diastolicRange),
        accent = ChartPalette.BLOOD_PRESSURE,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BloodPressurePlot(chart, viewport, Modifier.height(PREVIEW_HEIGHT), showAxes = false)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendEntry(ChartPalette.SYSTOLIC, BandText.systolic[lang])
                LegendEntry(ChartPalette.DIASTOLIC, BandText.diastolic[lang])
            }
        }
    }
}

@Composable
fun SleepPreviewCard(chart: SleepChart, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val latest = chart.latest
    // The preview spans exactly the last night, not a rolling 24 hours: a night is 6–9 hours, so a
    // 24-hour window would squeeze it into a third of the card and fill the rest with the daytime,
    // which has no sleep in it by definition.
    val viewport = remember(latest) {
        val end = latest?.endMs ?: System.currentTimeMillis()
        val span = ((latest?.let { it.endMs - it.startMs } ?: 0L) + 20 * 60_000L)
            .coerceAtLeast(ChartViewport.MIN_SPAN_MS)
        ChartViewport(initialEndMs = end + 10 * 60_000L, initialSpanMs = span)
    }
    ChartCard(
        title = BandText.sleep[lang],
        headline = chart.headline[lang],
        unit = "",
        band = null,
        subtitle = latest?.let {
            BandText.sleepBreakdown[lang].format(it.deep, it.light, it.rem, it.awake)
        } ?: BandText.noSleepRecord[lang],
        accent = ChartPalette.SLEEP_DEEP,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SleepPlot(chart, viewport, Modifier.height(PREVIEW_HEIGHT), showAxes = false)
            SleepLegend()
        }
    }
}

@Composable
private fun rememberPreviewViewport(bounds: LongRange): ChartViewport = remember(bounds) {
    ChartViewport(
        initialEndMs = bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
        initialSpanMs = ChartViewport.DEFAULT_SPAN_MS,
    )
}

@Composable
private fun EmptyPlot() {
    Box(
        Modifier.fillMaxWidth().height(PREVIEW_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            BandText.noReadings[LocalBandLanguage.current],
            style = MaterialTheme.typography.bodySmall,
            color = ChartPalette.AXIS_TEXT,
        )
    }
}
