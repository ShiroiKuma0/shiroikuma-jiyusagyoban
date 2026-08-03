package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One line metric, drawn.
 *
 * S6 of the pipeline: a data→pixel affine transform and a Path build, and nothing else. Everything
 * that decides *what* to draw already happened off the UI thread.
 *
 * Three draw layers, cheapest first:
 *  1. static-in-y (`drawWithCache`) — gridlines, y labels, border. Survives pan entirely.
 *  2. viewport (`drawBehind`) — x ticks, gap tints, the curve, the sample dots.
 *  3. overlay — the crosshair, only while scrubbing (phase 5).
 *
 * The viewport is read INSIDE the draw lambdas, never in the composable body. Reading it in draw
 * scope registers a draw-phase dependency only, so a pinch invalidates draw and not composition —
 * the difference between 2 ms and 20 ms a frame.
 */

/** Colours split into chrome and series, because a multi-series chart in one hue is unreadable. */
data class ChartColors(
    val background: Color,
    val border: Color,
    val gridline: Color,
    val majorTick: Color,
    val minorTick: Color,
    val label: Color,
    val series: Color,
    val dot: Color,
    val spot: Color,
    val gap: Color,
    val rejected: Color,
)

@Composable
fun rememberChartColors(series: Color): ChartColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme, series) {
        ChartColors(
            background = scheme.surfaceVariant.copy(alpha = 0.25f),
            border = scheme.outlineVariant,
            gridline = scheme.outlineVariant.copy(alpha = 0.35f),
            majorTick = scheme.outlineVariant.copy(alpha = 0.75f),
            minorTick = scheme.outlineVariant.copy(alpha = 0.25f),
            label = scheme.onSurfaceVariant,
            series = series,
            dot = series,
            spot = scheme.tertiary,
            gap = scheme.error.copy(alpha = 0.10f),
            rejected = scheme.error,
        )
    }
}

@Composable
fun MetricLineChart(
    spec: MetricSpec,
    model: RenderModel,
    viewport: ChartViewport,
    zone: ZoneId,
    colors: ChartColors,
    modifier: Modifier = Modifier,
    height: Dp = 148.dp,
    showRejected: Boolean = false,
    onToggleRejected: () -> Unit = {},
    /**
     * Real measurements taken under a different mode, drawn as unconnected spots rather than joined
     * into the line. Used for the SpO₂-coincident heart-rate population, which runs ~7 bpm high.
     */
    spotReadings: List<ChartPoint> = emptyList(),
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(colors.label) { TextStyle(fontSize = 10.sp, color = colors.label) }

    // The y band: the metric's clinical range, expanded only far enough to contain the data. Never
    // auto-ranged per window — a scale that jumps as you pan destroys the ability to judge magnitude
    // by eye.
    val lo = remember(spec, model.dataMin) {
        if (model.dataMin >= spec.yMin) spec.yMin else floorTo(model.dataMin, axisStep(spec))
    }
    val hi = remember(spec, model.dataMax) {
        if (model.dataMax <= spec.yMax) spec.yMax else ceilTo(model.dataMax, axisStep(spec))
    }

    val yLabels = remember(lo, hi, spec, labelStyle) {
        niceSteps(lo, hi).map { it to measurer.measure(spec.format(it), labelStyle) }
    }

    // Hoisted out of the draw lambda: allocating a Path or a Paint per frame is the classic Compose
    // chart performance bug.
    val path = remember { Path() }
    val dotScratch = remember { FloatArray(4096) }
    val dotPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    val gutterLeft = with(density) { 34.dp.toPx() }
    val gutterBottom = with(density) { 16.dp.toPx() }
    val dotMinSpacing = with(density) { 6.dp.toPx() }
    val strokeWidth = with(density) { 1.8.dp.toPx() }

    val description = remember(spec, model.stats) {
        "${spec.label} chart. ${model.stats.summary()}. " +
            if (model.isEmpty) "No data in this window." else "Range ${spec.format(model.dataMin)} to ${spec.format(model.dataMax)} ${spec.unit}."
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${spec.label}  ${spec.unit}", style = MaterialTheme.typography.labelLarge, color = colors.label)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .onSizeChanged { viewport.plotWidthPx = (it.width - gutterLeft).coerceAtLeast(1f) }
                // LAYER 1 — static in y. Survives pan; only size, theme and the y band invalidate it.
                .drawWithCache {
                    val plot = plotRect(size, gutterLeft, gutterBottom)
                    onDrawBehind {
                        drawRect(
                            color = colors.background,
                            topLeft = Offset(plot.left, plot.top),
                            size = Size(plot.width, plot.height),
                        )
                        for ((value, layout) in yLabels) {
                            val y = plot.yOf(value, lo, hi)
                            if (y < plot.top - 1f || y > plot.bottom + 1f) continue
                            drawLine(
                                color = colors.gridline,
                                start = Offset(plot.left, y),
                                end = Offset(plot.right, y),
                                strokeWidth = 1f,
                            )
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    x = plot.left - layout.size.width - 4f,
                                    y = y - layout.size.height / 2f,
                                ),
                            )
                        }
                        drawRect(
                            color = colors.border,
                            topLeft = Offset(plot.left, plot.top),
                            size = Size(plot.width, plot.height),
                            style = Stroke(width = 1f),
                        )
                        // The truncated-axis marker. SpO2 is drawn from 88 rather than 70 because a
                        // full axis makes a real desaturation a couple of pixels tall; the marker is
                        // what keeps that truncation honest instead of hidden.
                        if (spec.axisBreak) drawAxisBreak(plot, colors.border)
                    }
                }
                // LAYER 2 — the viewport. Everything here reads viewport state in DRAW scope.
                .drawBehind {
                    val plot = plotRect(size, gutterLeft, gutterBottom)
                    val startMs = viewport.startMs
                    val endMs = viewport.endMs
                    val spanMs = (endMs - startMs).coerceAtLeast(1L)
                    fun xOf(tMs: Long) = plot.left + (tMs - startMs).toFloat() / spanMs * plot.width

                    for (gap in model.gaps) {
                        val x0 = xOf(gap.first).coerceIn(plot.left, plot.right)
                        val x1 = xOf(gap.last).coerceIn(plot.left, plot.right)
                        if (x1 - x0 < 0.5f) continue
                        // A tint, never a dashed connector — a dashed line across a gap reads as data.
                        drawRect(
                            color = colors.gap,
                            topLeft = Offset(x0, plot.top),
                            size = Size(x1 - x0, plot.height),
                        )
                    }

                    for (tick in ChartTicks.labelled(ChartTicks.forSpan(startMs, endMs, zone))) {
                        val x = xOf(tick.tMs)
                        if (x < plot.left || x > plot.right) continue
                        drawLine(
                            color = if (tick.major) colors.majorTick else colors.minorTick,
                            start = Offset(x, plot.top),
                            end = Offset(x, plot.bottom),
                            strokeWidth = if (tick.major) 1.4f else 1f,
                        )
                        if (tick.major && tick.label.isNotEmpty()) {
                            val layout = measurer.measure(tick.label, labelStyle)
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(
                                    x = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                                    y = plot.bottom + 2f,
                                ),
                            )
                        }
                    }

                    for (segment in model.segments) {
                        path.reset()
                        if (segment.beziers.isEmpty()) {
                            // A single-point segment, or linear mode: a polyline through the samples.
                            segment.points.forEachIndexed { i, p ->
                                val x = xOf(p.tMs)
                                val y = plot.yOf(p.value, lo, hi)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                        } else {
                            val first = segment.beziers.first()
                            path.moveTo(xOf(first.x0.toLong()), plot.yOf(first.y0, lo, hi))
                            for (b in segment.beziers) {
                                path.cubicTo(
                                    xOf(b.c1x.toLong()), plot.yOf(b.c1y, lo, hi),
                                    xOf(b.c2x.toLong()), plot.yOf(b.c2y, lo, hi),
                                    xOf(b.x1.toLong()), plot.yOf(b.y1, lo, hi),
                                )
                            }
                        }
                        drawPath(path, color = colors.series, style = Stroke(width = strokeWidth))
                    }

                    // The real samples, on top of the curve. PCHIP flattens at a local extremum, so
                    // a genuine single-sample peak renders as a slight plateau; these dots are the
                    // measurements themselves, and answer the brief directly.
                    val totalPoints = model.segments.sumOf { it.points.size }
                    if (totalPoints > 1 && plot.width / totalPoints >= dotMinSpacing) {
                        dotPaint.color = colors.dot.toArgb()
                        dotPaint.strokeWidth = strokeWidth * 1.7f
                        dotPaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        var n = 0
                        for (segment in model.segments) {
                            for (p in segment.points) {
                                if (n + 2 > dotScratch.size) break
                                dotScratch[n++] = xOf(p.tMs)
                                dotScratch[n++] = plot.yOf(p.value, lo, hi)
                            }
                        }
                        // One dispatch instead of one per dot — DrawScope has no batch primitive.
                        drawContext.canvas.nativeCanvas.drawPoints(dotScratch, 0, n, dotPaint)
                    }

                    if (spotReadings.isNotEmpty()) {
                        for (p in spotReadings) {
                            val x = xOf(p.tMs)
                            if (x < plot.left || x > plot.right) continue
                            drawCircle(
                                color = colors.spot,
                                radius = strokeWidth * 1.1f,
                                center = Offset(x, plot.yOf(p.value, lo, hi)),
                                style = Stroke(width = 1.2f),
                            )
                        }
                    }

                    if (showRejected) {
                        for (p in model.rejectedPoints) {
                            val x = xOf(p.tMs)
                            if (x < plot.left || x > plot.right) continue
                            val y = plot.yOf(p.value, lo, hi)
                            val r = strokeWidth * 2f
                            // A hollow ✕ at the sample's REAL value — what was dropped, and where.
                            drawLine(colors.rejected, Offset(x - r, y - r), Offset(x + r, y + r), 1.4f)
                            drawLine(colors.rejected, Offset(x - r, y + r), Offset(x + r, y - r), 1.4f)
                        }
                    }
                },
        )

        // Not debug output. 白い熊 cares that the chart stays close to the measurements, so the app
        // has to be able to PROVE what it dropped — that is what makes the filtering acceptable.
        Text(
            text = model.stats.summary() + if (model.stats.rejected > 0) "  (tap)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.label,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = model.stats.rejected > 0) { onToggleRejected() }
                .padding(top = 2.dp),
        )
    }
}

/** The plot area, inside the gutters that hold the axis labels. */
private class PlotRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun yOf(value: Double, lo: Double, hi: Double): Float {
        if (hi <= lo) return bottom
        return bottom - ((value - lo) / (hi - lo)).toFloat() * height
    }
}

private fun plotRect(size: Size, gutterLeft: Float, gutterBottom: Float) = PlotRect(
    left = gutterLeft,
    top = 2f,
    right = size.width,
    bottom = size.height - gutterBottom,
)

/** Two short diagonals at the baseline: the axis does not start at zero, and says so. */
private fun DrawScope.drawAxisBreak(plot: PlotRect, color: Color) {
    val y = plot.bottom
    val w = 5f
    for (dy in listOf(-3f, 1f)) {
        drawLine(
            color = color,
            start = Offset(plot.left - w, y + dy + 3f),
            end = Offset(plot.left + w, y + dy - 3f),
            strokeWidth = 1.4f,
        )
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

/**
 * The rounding unit for expanding the axis, taken from the metric's OWN band.
 *
 * A fixed unit cannot work across these metrics: five was fine for heart rate but rounded a 36.5 °C
 * reading up to a 40 °C axis, squashing a 4-degree clinical band into the bottom third of the plot.
 */
private fun axisStep(spec: MetricSpec): Double = niceStep((spec.yMax - spec.yMin) / 4.0)

private fun niceStep(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw - 1e-9 }
}

/** Round outward to a readable boundary rather than to the exact data extreme. */
private fun floorTo(v: Double, step: Double): Double = Math.floor(v / step) * step
private fun ceilTo(v: Double, step: Double): Double = Math.ceil(v / step) * step

/** Four or five gridlines, on round numbers. */
private fun niceSteps(lo: Double, hi: Double): List<Double> {
    if (hi <= lo) return listOf(lo)
    val step = niceStep((hi - lo) / 4.0)
    val first = Math.ceil(lo / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= hi + 1e-9 }.toList()
}

/** Only used by the empty state. */
internal val CHART_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun Long.atZoneLabel(zone: ZoneId): String =
    Instant.ofEpochMilli(this).atZone(zone).format(CHART_TIME)
