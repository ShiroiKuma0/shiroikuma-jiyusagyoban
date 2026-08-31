package com.opentasker.ui.charts.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.opentasker.ui.charts.ChartCurveMode
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

// The mark weights are no longer compiled in: they come off the frame's [ChartStyle], which reads
// them from ThemePrefs. They are also now expressed in **dp rather than raw pixels**, which fixes a
// quiet density bug — the old `STROKE = 2f` was two device pixels, so on a 3x screen the line was a
// third of the weight the method calls for, and got thinner the better the display.

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
    val style = frame.style
    val stroke = style.lineWidth.toPx()
    val dotRadius = style.dotSize.toPx() / 2f
    for (seg in segments) {
        if (seg.points.isEmpty()) continue
        val path = Path()
        var started = false
        if (seg.beziers.isEmpty()) {
            // No Bézier control points means LINEAR or STEP. A step draws the value as held until the
            // next sample, which is the honest rendering for something read at a fixed cadence and
            // constant in between; a straight join claims a ramp nobody measured.
            var lastY = 0f
            for (p in seg.points) {
                val x = frame.x(p.tMs)
                val y = frame.y(p.value)
                when {
                    !started -> { path.moveTo(x, y); started = true }
                    style.curve == ChartCurveMode.STEP -> { path.lineTo(x, lastY); path.lineTo(x, y) }
                    else -> path.lineTo(x, y)
                }
                lastY = y
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
            if (style.fillAlpha > 0f) {
                drawPath(
                    fill,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = style.fillAlpha), color.copy(alpha = 0f)),
                        startY = frame.rect.top,
                        endY = frame.rect.bottom,
                    ),
                )
            }
        }
        glowStroke(path, color, stroke, style.glowAlpha)

        if (showDots && style.showDots && dotRadius > 0f) {
            for (p in seg.points) {
                if (!frame.visible(p.tMs)) continue
                drawCircle(color, radius = dotRadius, center = Offset(frame.x(p.tMs), frame.y(p.value)))
            }
        }
    }
}

/**
 * Individual readings as dots — one measurement, one mark, nothing aggregated or decimated.
 *
 * Used for a **second measurement population** that must not join the curve. Heart rate is the only
 * one: the readings taken alongside SpO₂ track exertion where the periodic series does not (asleep
 * and still the two agree to 1 bpm; with a hundred steps nearby the spot reading runs 22 bpm
 * higher), so joining them into one line would draw a sawtooth that is an artefact of the
 * interleaving. Drawn [hollow] they read as what they are — separate records, sitting above the
 * baseline curve where the moment was busier than the baseline knows.
 *
 * LTTB is deliberately not in this path. These dots ARE the measurements; decimating them would
 * defeat the purpose, and it is not needed — a day carries ~140 of them, and `drawPoints` batches
 * the filled case into one call with round caps.
 */
fun DrawScope.drawPoints(
    frame: PlotFrame,
    points: List<ChartPoint>,
    color: Color,
    hollow: Boolean = false,
) {
    if (points.isEmpty()) return
    val style = frame.style
    // Half the line's dot size again: these are readings in their own right, not punctuation on a
    // curve, and at the size used for the latter they read as dust.
    val radius = (style.dotSize.toPx() / 2f * 1.5f).coerceAtLeast(1.5f)
    val visible = ArrayList<Offset>(points.size)
    for (p in points) {
        if (!frame.visible(p.tMs)) continue
        visible += Offset(frame.x(p.tMs), frame.y(p.value))
    }
    if (visible.isEmpty()) return
    if (hollow) {
        // One at a time: there is no batched stroked-circle primitive. They are a fifth of the
        // stream at most — six an hour against the periodic series' twenty-four.
        val ring = (radius * 0.5f).coerceAtLeast(1f)
        for (at in visible) drawCircle(color, radius = radius, center = at, style = Stroke(width = ring))
    } else {
        drawPoints(
            points = visible,
            pointMode = PointMode.Points,
            color = color,
            strokeWidth = radius * 2f,
            cap = StrokeCap.Round,
        )
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
    val w = (hourPx - 2f).coerceIn(3f, frame.style.capsuleWidth.toPx())
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
    val w = (hourPx - 2f).coerceIn(3f, frame.style.dumbbellWidth.toPx())
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
        val h = (rowH * frame.style.hypnogramBand).coerceAtLeast(4f)
        val radius = frame.style.cornerRadius.toPx()
        drawRoundRect(
            color = colorOf(r.code),
            topLeft = Offset(x0, cy - h / 2f),
            size = Size((x1 - x0).coerceAtLeast(1.5f), h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
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
    val w = (bucketPx - 2f).coerceIn(1.5f, frame.style.barWidth.toPx())
    val radius = frame.style.cornerRadius.toPx()
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
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
    }
}
