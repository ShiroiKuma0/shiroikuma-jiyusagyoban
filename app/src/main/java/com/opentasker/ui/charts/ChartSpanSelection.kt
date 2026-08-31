package com.opentasker.ui.charts

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A marked stretch of time on a chart, and the arithmetic over it.
 *
 * The crosshair answers "what was it at 03:12". This answers the other question a chart of steps
 * provokes — "how many did that walk come to" — which the eye cannot do from bars and the fixed
 * 1h/6h/24h chips cannot express, because a walk does not start on the hour.
 *
 * ## The gesture
 *
 * Long-press then drag. On the detail screen a tap is already the crosshair and a horizontal drag is
 * already the pan, so long-press-and-drag is what is left — and `detectDragGesturesAfterLongPress`
 * claims it only once the finger has been still, which is exactly what keeps a pan and a scroll flick
 * reaching their usual handlers. It is the same gesture the dashboard uses for its crosshair, for the
 * same reason: it is the one that does not collide.
 *
 * The span **stays** after the finger lifts, like the detail screen's crosshair — you mark the walk,
 * then pinch and pan around it while the total sits there. A long-press without a drag clears it.
 */
@Stable
class SpanSelectionState {
    /** Where the drag began, in epoch millis. Null when nothing is marked. */
    var anchorMs: Long? by mutableStateOf(null)
        private set

    /** Where it has been dragged to. Null when nothing is marked. */
    var cursorMs: Long? by mutableStateOf(null)
        private set

    val active: Boolean get() = anchorMs != null && cursorMs != null

    /** The span in chronological order, regardless of which way the finger went. */
    val startMs: Long? get() = anchorMs?.let { a -> cursorMs?.let { c -> minOf(a, c) } }
    val endMs: Long? get() = anchorMs?.let { a -> cursorMs?.let { c -> maxOf(a, c) } }

    fun begin(t: Long) { anchorMs = t; cursorMs = t }
    fun dragTo(t: Long) { if (anchorMs != null) cursorMs = t }
    fun clear() { anchorMs = null; cursorMs = null }

    /**
     * Set both ends outright — for nudging a span rather than drawing one.
     *
     * A finger on a six-hour chart resolves to roughly a minute per pixel at best, which is fine for
     * reading a total and useless for saying when a workout began. The mark-a-session screen needs
     * the drag for the rough shape and arrows for the edges, and both write here.
     */
    fun set(start: Long, end: Long) { anchorMs = minOf(start, end); cursorMs = maxOf(start, end) }
}

@Composable
fun rememberSpanSelectionState(): SpanSelectionState = remember { SpanSelectionState() }

/**
 * Long-press and drag to mark a span; a long-press that never moves clears it.
 *
 * [viewport] is read at gesture time rather than captured, so a chart panned or zoomed since
 * composition still maps the touch to the right instant.
 */
fun Modifier.spanSelectInput(
    selection: SpanSelectionState,
    viewport: ChartViewport,
): Modifier = pointerInput(selection, viewport) {
    fun timeAt(x: Float): Long {
        val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
        return viewport.startMs + (fraction * viewport.spanMs).toLong()
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { offset -> selection.begin(timeAt(offset.x)) },
        onDrag = { change, _ -> selection.dragTo(timeAt(change.position.x)) },
        // A press that never became a drag is how you take the marking away again.
        onDragEnd = { if (selection.startMs == selection.endMs) selection.clear() },
        onDragCancel = { selection.clear() },
    )
}

/** What a marked span adds up to. */
data class SpanTotals(
    val count: Int,
    val sum: Double,
    val mean: Double,
    val min: Double,
    val max: Double,
) {
    companion object {
        /**
         * Totals over the samples inside [startMs]..[endMs].
         *
         * The samples, never the drawn curve: the curve is an interpolation and summing it would
         * invent steps that were never taken. Returns null when the span caught nothing, so the
         * readout can say so rather than show a confident zero.
         */
        fun of(points: List<ChartPoint>, startMs: Long, endMs: Long): SpanTotals? {
            val inSpan = points.filter { it.tMs in startMs..endMs }
            if (inSpan.isEmpty()) return null
            val values = inSpan.map { it.value }
            return SpanTotals(
                count = inSpan.size,
                sum = values.sum(),
                mean = values.average(),
                min = values.min(),
                max = values.max(),
            )
        }
    }
}

/**
 * The line under the chart that reports what the marked span came to.
 *
 * Steps are **summed** — that is the question a stretch of a step chart asks, and the one the fixed
 * 1h/6h/24h chips cannot answer because a walk does not begin on the hour. Every other metric is
 * **averaged** with its range beside it, because summing a heart rate is meaningless.
 */
@Composable
fun SpanReadout(selection: SpanSelectionState, chart: MetricChart, lang: BandLanguage) {
    val style = LocalChartStyle.current
    val start = selection.startMs
    val end = selection.endMs

    if (start == null || end == null) {
        Text(
            BandText.spanHint[lang],
            style = MaterialTheme.typography.bodySmall,
            color = style.axisText,
        )
        return
    }

    val totals = SpanTotals.of(chart.readoutPoints, start, end)
    val window = "${BandDates.dateTime(start)} – ${BandDates.time(end)}"
    Text(
        text = if (totals == null) {
            "$window · ${BandText.spanEmpty[lang]}"
        } else {
            // A BARS render IS a count here — the same test MetricHistoryCard uses to decide whether
            // its column is a total. Summing a measurement like heart rate is not a quantity.
            val figure = if (chart.spec.render == RenderKind.BARS) {
                "${BandText.spanTotal[lang]} ${"%,d".format(totals.sum.toLong())} ${chart.spec.unit}"
            } else {
                "${BandText.spanMean[lang]} ${chart.spec.format(totals.mean)} · " +
                    "${chart.spec.format(totals.min)}–${chart.spec.format(totals.max)}"
            }
            "$window · $figure · ${BandText.spanSamples[lang].format(totals.count)}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
