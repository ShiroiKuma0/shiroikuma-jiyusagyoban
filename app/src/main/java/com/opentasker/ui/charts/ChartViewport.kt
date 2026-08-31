package com.opentasker.ui.charts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * The shared time window, expressed as a SPAN IN MILLISECONDS rather than a scale factor.
 *
 * That choice is what makes everything else fall out cleanly: the tick ladder, the query window and
 * the decimation target all derive from the span directly, and the value is legible to a human —
 * "6 hours" beats "scale 4.31".
 *
 * **One instance shared across every chart.** The entire value of a stacked column of health charts
 * is cross-reading — "HR spiked at 03:12, was I in REM?" — and independent per-chart zoom destroys
 * that and triples the query load.
 *
 * The state is backed by Compose snapshot state so that a gesture invalidates DRAW only. Read these
 * properties *inside* the draw lambda, never in the composable body: reading them in draw scope
 * registers a draw-phase dependency, so a pinch redraws without recomposing the subtree. Read them in
 * the body and every frame recomposes everything — the difference between 2 ms and 20 ms.
 */
@Stable
class ChartViewport(
    initialEndMs: Long,
    initialSpanMs: Long = DEFAULT_SPAN_MS,
) {
    /** Right edge of the window. */
    var endMs by mutableLongStateOf(initialEndMs)
        private set

    /** Visible duration. */
    var spanMs by mutableLongStateOf(initialSpanMs)
        private set

    /** Plot width in pixels, published by the renderer once it knows its size. */
    var plotWidthPx by mutableFloatStateOf(0f)

    /** True while a gesture is in flight — the pipeline holds its level of detail steady. */
    var isInteracting by androidx.compose.runtime.mutableStateOf(false)

    val startMs: Long get() = endMs - spanMs

    fun xOf(tMs: Long): Float =
        if (plotWidthPx <= 0f) 0f else (tMs - startMs).toFloat() / spanMs * plotWidthPx

    fun tOf(x: Float): Long =
        if (plotWidthPx <= 0f) startMs else startMs + (x / plotWidthPx * spanMs).toLong()

    /**
     * Pinch, anchored on the finger centroid: the timestamp under the centroid is invariant across
     * the zoom. Anchoring on the plot centre instead is the thing that makes a chart feel like it is
     * fighting you.
     */
    fun zoomAround(focalX: Float, zoomChange: Float, bounds: LongRange) {
        if (zoomChange <= 0f || plotWidthPx <= 0f) return
        val focalT = tOf(focalX)
        val newSpan = (spanMs / zoomChange).toLong().coerceIn(MIN_SPAN_MS, maxSpanFor(bounds))
        // Keep focalT under focalX: focalT = newStart + (focalX / width) * newSpan
        val fraction = focalX / plotWidthPx
        val newStart = focalT - (fraction * newSpan).toLong()
        spanMs = newSpan
        endMs = clampEnd(newStart + newSpan, bounds)
    }

    fun panBy(dxPx: Float, bounds: LongRange) {
        if (plotWidthPx <= 0f) return
        val deltaMs = (dxPx / plotWidthPx * spanMs).toLong()
        endMs = clampEnd(endMs - deltaMs, bounds)
    }

    /** Jump to a named span, keeping the right edge — what the 24h / 6h / 1h chips do. */
    fun setSpan(newSpanMs: Long, bounds: LongRange) {
        spanMs = newSpanMs.coerceIn(MIN_SPAN_MS, maxSpanFor(bounds))
        endMs = clampEnd(endMs, bounds)
    }

    /** Snap the right edge to now. */
    fun jumpTo(endMs: Long, bounds: LongRange) {
        this.endMs = clampEnd(endMs, bounds)
    }

    /**
     * Pan is clamped so the window always overlaps the data, with a quarter-window of overscroll on
     * the right so "now" is not pinned hard against the edge.
     */
    private fun clampEnd(candidate: Long, bounds: LongRange): Long {
        val hardMax = bounds.last + spanMs / 4
        val hardMin = bounds.first + spanMs
        if (hardMin > hardMax) return hardMax
        return candidate.coerceIn(hardMin, hardMax)
    }

    private fun maxSpanFor(bounds: LongRange): Long {
        val available = bounds.last - bounds.first
        // No ceiling beyond the data itself — 24 h is the starting span, not the maximum.
        return maxOf(available + available / 4, DEFAULT_SPAN_MS)
    }

    companion object {
        const val DEFAULT_SPAN_MS = 24 * 3_600_000L

        /**
         * Floor of ten minutes. At a 120 s cadence that is five real samples across the plot — the
         * point at which individual measurements become countable. Below it you are inspecting the
         * interpolation rather than the data.
         */
        const val MIN_SPAN_MS = 10 * 60_000L
    }
}
