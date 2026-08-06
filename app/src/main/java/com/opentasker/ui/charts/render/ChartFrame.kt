package com.opentasker.ui.charts.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.ChartTick
import com.opentasker.ui.charts.ChartViewport

/**
 * The parts every chart shares: the plot rectangle, the axes, the grid, and the gap tint.
 *
 * Grid and axes are deliberately recessive — they orient the eye and then get out of the way. A grid
 * competing with the data is the most common way a chart ends up harder to read than the numbers it
 * replaced.
 *
 * Nothing here interpolates or aggregates. Every renderer that draws a mark receives values that were
 * measured; this file only decides where on the canvas they land.
 */

/**
 * Maps data space to pixels for one plot. Built per frame, cheap, immutable.
 *
 * It also carries the [ChartStyle]. Every mark function below is a `DrawScope` extension rather than
 * a composable, so none of them can read a CompositionLocal; the plot composable reads it once and
 * hands it down here with the geometry, which keeps the settable values and the frame they apply to
 * impossible to get out of step.
 */
class PlotFrame(
    val rect: Rect,
    val viewport: ChartViewport,
    val yMin: Double,
    val yMax: Double,
    val style: ChartStyle = ChartStyle.DEFAULT,
) {
    fun x(tMs: Long): Float =
        rect.left + (tMs - viewport.startMs).toFloat() / viewport.spanMs * rect.width

    fun y(value: Double): Float {
        val span = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0
        val t = ((value - yMin) / span).coerceIn(-0.5, 1.5)
        return rect.bottom - (t * rect.height).toFloat()
    }

    /** True when a timestamp is inside the visible window, with a margin so marks clip cleanly. */
    fun visible(tMs: Long, marginMs: Long = 0L): Boolean =
        tMs >= viewport.startMs - marginMs && tMs <= viewport.endMs + marginMs
}

private fun PlotFrame.axisTextStyle() =
    TextStyle(fontSize = style.axisTextSize, color = style.axisText)

fun DrawScope.drawGrid(frame: PlotFrame, ticks: List<ChartTick>, horizontalLines: Int = 4) {
    if (!frame.style.showGrid) return
    for (i in 0..horizontalLines) {
        val y = frame.rect.top + frame.rect.height * i / horizontalLines
        drawLine(
            color = frame.style.grid,
            start = Offset(frame.rect.left, y),
            end = Offset(frame.rect.right, y),
            strokeWidth = 1f,
        )
    }
    for (t in ticks) {
        if (!frame.visible(t.tMs)) continue
        val x = frame.x(t.tMs)
        drawLine(
            color = frame.style.grid,
            start = Offset(x, frame.rect.top),
            end = Offset(x, frame.rect.bottom),
            strokeWidth = if (t.major) 1f else 0.5f,
        )
    }
}

/**
 * Stretches with no measurement, drawn as a tint.
 *
 * **Never a dashed connector across a gap** — a dashed line reads as "the value went smoothly from
 * here to there", which is precisely the claim we cannot make. An absence has to look like an absence.
 */
fun DrawScope.drawGaps(frame: PlotFrame, gaps: List<LongRange>) {
    if (!frame.style.showGaps) return
    for (g in gaps) {
        if (g.last < frame.viewport.startMs || g.first > frame.viewport.endMs) continue
        val x0 = frame.x(g.first).coerceAtLeast(frame.rect.left)
        val x1 = frame.x(g.last).coerceAtMost(frame.rect.right)
        if (x1 <= x0) continue
        drawRect(
            color = frame.style.gapTint,
            topLeft = Offset(x0, frame.rect.top),
            size = Size(x1 - x0, frame.rect.height),
        )
    }
}

fun DrawScope.drawTimeLabels(
    frame: PlotFrame,
    ticks: List<ChartTick>,
    measurer: TextMeasurer,
) {
    var lastRight = Float.NEGATIVE_INFINITY
    for (t in ticks) {
        if (t.label.isBlank() || !frame.visible(t.tMs)) continue
        val laid: TextLayoutResult = measurer.measure(t.label, frame.axisTextStyle())
        val x = frame.x(t.tMs) - laid.size.width / 2f
        // Drop a label rather than overlap one — an unreadable axis is worse than a sparse one.
        if (x < lastRight + 8f) continue
        if (x < frame.rect.left - 4f || x + laid.size.width > frame.rect.right + 4f) continue
        drawText(laid, topLeft = Offset(x, frame.rect.bottom + 4f))
        lastRight = x + laid.size.width
    }
}

fun DrawScope.drawValueLabels(
    frame: PlotFrame,
    measurer: TextMeasurer,
    format: (Double) -> String,
    lines: Int = 4,
) {
    for (i in 0..lines) {
        val value = frame.yMax - (frame.yMax - frame.yMin) * i / lines
        val laid = measurer.measure(format(value), frame.axisTextStyle())
        val y = frame.rect.top + frame.rect.height * i / lines - laid.size.height / 2f
        drawText(laid, topLeft = Offset(frame.rect.right + 6f, y))
    }
}

/**
 * The break marker for a truncated axis.
 *
 * SpO₂ is drawn from 88 % rather than 0, because on a full axis a real desaturation is a couple of
 * pixels. Truncating an axis is legitimate; truncating it *silently* is not, and this mark is the
 * difference.
 */
fun DrawScope.drawAxisBreak(frame: PlotFrame) {
    val y = frame.rect.bottom
    val w = 7f
    var x = frame.rect.left
    while (x < frame.rect.left + 22f) {
        drawLine(
            color = frame.style.axisText,
            start = Offset(x, y + 3f),
            end = Offset(x + w / 2f, y - 3f),
            strokeWidth = 1.5f,
        )
        x += w
    }
}

/**
 * The shared crosshair: one vertical line, plus a ring on the sample it is reading.
 *
 * Drawn last, over everything, because its whole job is to be findable while a finger is on the
 * screen. The line is the accent colour rather than a series colour — it belongs to the gesture, not
 * to any one metric.
 */
fun DrawScope.drawCrosshair(
    frame: PlotFrame,
    tMs: Long,
    accent: Color,
    marker: com.opentasker.ui.charts.ChartPoint? = null,
) {
    if (!frame.visible(tMs)) return
    val x = frame.x(tMs)
    drawLine(
        color = accent.copy(alpha = 0.75f),
        start = Offset(x, frame.rect.top),
        end = Offset(x, frame.rect.bottom),
        strokeWidth = 1.5f,
    )
    marker?.let {
        val cy = frame.y(it.value)
        drawCircle(accent, radius = 5.5f, center = Offset(x, cy))
        drawCircle(Color.Black, radius = 2.5f, center = Offset(x, cy))
    }
}

/** A hollow ✕ at a flagged sample's REAL value — the proof of what the filter dropped. */
fun DrawScope.drawRejected(frame: PlotFrame, points: List<com.opentasker.ui.charts.ChartPoint>) {
    if (!frame.style.showRejected) return
    for (p in points) {
        if (!frame.visible(p.tMs)) continue
        val cx = frame.x(p.tMs)
        val cy = frame.y(p.value)
        val r = 4f
        drawLine(ChartPalette.REJECTED, Offset(cx - r, cy - r), Offset(cx + r, cy + r), 1.5f)
        drawLine(ChartPalette.REJECTED, Offset(cx - r, cy + r), Offset(cx + r, cy - r), 1.5f)
    }
}

/** A soft glow beneath a stroke, so a bright series reads on a near-black surface. */
fun DrawScope.glowStroke(
    path: androidx.compose.ui.graphics.Path,
    color: Color,
    width: Float,
    glowAlpha: Float = 0.18f,
) {
    if (glowAlpha > 0f) {
        drawPath(path, color = color.copy(alpha = glowAlpha), style = Stroke(width = width * 3.5f))
    }
    drawPath(path, color = color, style = Stroke(width = width))
}
