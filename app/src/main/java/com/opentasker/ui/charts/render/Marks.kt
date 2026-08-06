package com.opentasker.ui.charts.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.DumbbellBucket
import com.opentasker.ui.charts.HourBucket
import com.opentasker.ui.charts.HourlyEnvelope
import com.opentasker.ui.charts.RenderSegment
import com.opentasker.ui.charts.SleepRun

/**
 * The marks themselves.
 *
 * Each function draws one kind of thing and nothing else — no axes, no aggregation, no decisions
 * about what the data means. The mark specs follow the data-viz method: thin strokes, rounded
 * data-ends, a surface-coloured gap between adjacent fills so touching marks stay countable, and
 * markers no smaller than 8 px so they remain hit-able and visible.
 */

private const val STROKE = 2f
private const val END_RADIUS = 2f

/**
 * A PCHIP curve through the retained samples, with the samples drawn on top.
 *
 * The dots matter more than the curve: they *are* the measurements, and drawing them over the line is
 * what keeps a smoothed chart honest. PCHIP flattens at a local extremum, so a genuine single-sample
 * peak renders as a slight plateau — the dot sitting on it is what tells you the peak was real.
 */
fun DrawScope.drawLineSeries(
    frame: PlotFrame,
    segments: List<RenderSegment>,
    color: Color,
    fillUnder: Boolean = true,
    showDots: Boolean = true,
) {
    for (seg in segments) {
        if (seg.points.isEmpty()) continue
        val path = Path()
        var started = false
        if (seg.beziers.isEmpty()) {
            for (p in seg.points) {
                val x = frame.x(p.tMs)
                val y = frame.y(p.value)
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
        } else {
            val first = seg.beziers.first()
            path.moveTo(frame.x(first.x0.toLong()), frame.y(first.y0))
            for (b in seg.beziers) {
                path.cubicTo(
                    frame.x(b.c1x.toLong()), frame.y(b.c1y),
                    frame.x(b.c2x.toLong()), frame.y(b.c2y),
                    frame.x(b.x1.toLong()), frame.y(b.y1),
                )
            }
            started = true
        }
        if (!started) continue

        if (fillUnder) {
            // The area is decoration, so it is faint and it fades: a solid block under the line
            // would out-weigh the line itself, which is where the actual information is.
            val fill = Path().apply { addPath(path) }
            val lastX = frame.x(seg.points.last().tMs)
            val firstX = frame.x(seg.points.first().tMs)
            fill.lineTo(lastX, frame.rect.bottom)
            fill.lineTo(firstX, frame.rect.bottom)
            fill.close()
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
                    startY = frame.rect.top,
                    endY = frame.rect.bottom,
                ),
            )
        }
        glowStroke(path, color, STROKE)

        if (showDots) {
            for (p in seg.points) {
                if (!frame.visible(p.tMs)) continue
                drawCircle(color, radius = 2.6f, center = Offset(frame.x(p.tMs), frame.y(p.value)))
            }
        }
    }
}

/**
 * One capsule per hour, spanning that hour's real minimum and maximum.
 *
 * This is Hume's `H` tab, and checking ours against theirs is what proved our decode: their
 * 2026-08-04 headline of 58–91 bpm against our pooled 58–91, capsule for capsule.
 *
 * A capsule is an honest mark because both of its ends are readings that occurred. A bar of hourly
 * means would be a number nobody measured.
 */
fun DrawScope.drawCapsules(
    frame: PlotFrame,
    buckets: List<HourBucket>,
    color: Color,
) {
    if (buckets.isEmpty()) return
    val hourPx = HourlyEnvelope.HOUR_MS.toFloat() / frame.viewport.spanMs * frame.rect.width
    // A 2px surface gap keeps neighbouring hours countable instead of merging into a ribbon.
    val w = (hourPx - 2f).coerceIn(3f, 22f)
    for (b in buckets) {
        val centre = b.startMs + HourlyEnvelope.HOUR_MS / 2
        if (!frame.visible(centre, HourlyEnvelope.HOUR_MS)) continue
        val cx = frame.x(centre)
        val yHi = frame.y(b.hi)
        val yLo = frame.y(b.lo)
        val h = (yLo - yHi).coerceAtLeast(w)   // a one-reading hour still shows as a round dot
        drawRoundRect(
            color = color,
            topLeft = Offset(cx - w / 2f, yHi - (h - (yLo - yHi)) / 2f),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
        )
    }
}

/**
 * Systolic over diastolic, one bar per hour, on ONE axis.
 *
 * Both series share a unit, so they share a scale. Giving the second its own y-axis is the dual-axis
 * mistake: with two independent scales any two series can be made to look correlated.
 */
fun DrawScope.drawDumbbells(
    frame: PlotFrame,
    buckets: List<DumbbellBucket>,
    upperColor: Color,
    lowerColor: Color,
) {
    if (buckets.isEmpty()) return
    val hourPx = HourlyEnvelope.HOUR_MS.toFloat() / frame.viewport.spanMs * frame.rect.width
    val w = (hourPx - 2f).coerceIn(3f, 14f)
    for (d in buckets) {
        val centre = d.startMs + HourlyEnvelope.HOUR_MS / 2
        if (!frame.visible(centre, HourlyEnvelope.HOUR_MS)) continue
        val cx = frame.x(centre)
        // A faint spine joins the pair so they read as one reading rather than two series that
        // happen to be near each other.
        val top = d.upper?.let { frame.y(it.hi) }
        val bottom = d.lower?.let { frame.y(it.lo) }
        if (top != null && bottom != null) {
            drawLine(
                color = upperColor.copy(alpha = 0.22f),
                start = Offset(cx, top), end = Offset(cx, bottom), strokeWidth = 1.5f,
            )
        }
        d.upper?.let { cap(frame, cx, w, it, upperColor) }
        d.lower?.let { cap(frame, cx, w, it, lowerColor) }
    }
}

private fun DrawScope.cap(frame: PlotFrame, cx: Float, w: Float, b: HourBucket, color: Color) {
    val yHi = frame.y(b.hi)
    val yLo = frame.y(b.lo)
    val h = (yLo - yHi).coerceAtLeast(w)
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - w / 2f, yHi - (h - (yLo - yHi)) / 2f),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
    )
}

/**
 * Sleep stages as a stepped ribbon.
 *
 * Categorical data, so **nothing is interpolated**: there is no value between "deep" and "REM", and a
 * smooth curve between them would draw a stage that never happened. The mark is a block per run, at
 * that stage's own row, with a riser joining consecutive runs so the night reads as one shape.
 */
fun DrawScope.drawHypnogram(
    frame: PlotFrame,
    runs: List<SleepRun>,
    rowOf: (Char) -> Int,
    rows: Int,
    colorOf: (Char) -> Color,
) {
    if (runs.isEmpty()) return
    val rowH = frame.rect.height / rows
    fun rowCentre(code: Char) = frame.rect.top + rowH * (rowOf(code) + 0.5f)

    var previous: SleepRun? = null
    for (r in runs) {
        if (r.endMs < frame.viewport.startMs || r.startMs > frame.viewport.endMs) { previous = r; continue }
        val x0 = frame.x(r.startMs)
        val x1 = frame.x(r.endMs)
        val cy = rowCentre(r.code)
        val h = (rowH * 0.52f).coerceAtLeast(4f)
        drawRoundRect(
            color = colorOf(r.code),
            topLeft = Offset(x0, cy - h / 2f),
            size = Size((x1 - x0).coerceAtLeast(1.5f), h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            style = Fill,
        )
        previous?.let { p ->
            if (p.endMs == r.startMs && p.code != r.code) {
                drawLine(
                    color = colorOf(r.code).copy(alpha = 0.55f),
                    start = Offset(x0, rowCentre(p.code)),
                    end = Offset(x0, cy),
                    strokeWidth = 1.5f,
                )
            }
        }
        previous = r
    }
}

/**
 * Counts in a bucket.
 *
 * Zero is a real measurement here — you stood still — so a zero-height bar is drawn as a baseline
 * tick rather than omitted. That is the opposite of every other metric in this app, where a missing
 * value means the band did not measure, and it is why steps are never run through the outlier filter.
 */
fun DrawScope.drawBars(
    frame: PlotFrame,
    points: List<ChartPoint>,
    bucketMs: Long,
    color: Color,
) {
    if (points.isEmpty()) return
    val bucketPx = bucketMs.toFloat() / frame.viewport.spanMs * frame.rect.width
    val w = (bucketPx - 2f).coerceIn(1.5f, 18f)
    for (p in points) {
        if (!frame.visible(p.tMs, bucketMs)) continue
        val x = frame.x(p.tMs + bucketMs / 2)
        val y = frame.y(p.value)
        val h = (frame.rect.bottom - y).coerceAtLeast(if (p.value > 0.0) 2f else 0f)
        if (h <= 0f) continue
        drawRoundRect(
            color = color,
            topLeft = Offset(x - w / 2f, frame.rect.bottom - h),
            size = Size(w, h),
            // Rounded at the data end, square at the baseline — the round end reads as the value.
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(END_RADIUS, END_RADIUS),
        )
    }
}
