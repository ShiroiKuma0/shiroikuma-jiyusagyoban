package com.opentasker.ui.charts

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * One vertical line through every chart at once.
 *
 * The entire value of a stacked column of health charts is **cross-reading** — "heart rate spiked at
 * 03:12; was I in REM, and did my oxygen dip?" Without this the only way to answer that is to open
 * each card separately and line the time axes up by eye, which is arithmetic the page should be doing.
 *
 * So the crosshair is deliberately **shared**: one gesture drives every chart, and every chart reads
 * out its own value at the same instant. That in turn requires every chart to be on the same time
 * axis, which is why the dashboard hands them all one [ChartViewport] rather than one each.
 *
 * ## Two ways in, because the two screens have different spare gestures
 *
 * On the **dashboard** a tap already means "open this metric", so the crosshair is
 * [crosshairInput]: long-press and drag, which `detectDragGesturesAfterLongPress` claims only once
 * the finger has been still — a scroll flick and a pinch both still reach their usual handlers.
 *
 * On the **full-screen detail** nothing is listening for a tap, so [crosshairTapInput] takes it: one
 * tap plants the line and it **stays** until tapped away. A persistent crosshair is what that screen
 * actually wants — you put it on the spike, then pinch and pan around it — and requiring a long
 * press there would have hidden the feature behind a gesture nobody thinks to try. Drag still pans
 * and pinch still zooms, because a tap is neither.
 */
@Stable
class CrosshairState {
    /** Where the line is, or null when nobody is holding it. */
    var tMs: Long? by mutableStateOf(null)
        private set

    val active: Boolean get() = tMs != null

    fun moveTo(t: Long) { tMs = t }
    fun clear() { tMs = null }
}

@Composable
fun rememberCrosshairState(): CrosshairState = remember { CrosshairState() }

/**
 * Long-press and drag to place the crosshair; lift to dismiss it.
 *
 * [plotWidth] and [viewport] convert the touch x into a timestamp. Both are read at gesture time
 * rather than captured, so a chart that has been resized or panned since composition still maps
 * correctly.
 */
fun Modifier.crosshairInput(
    state: CrosshairState,
    viewport: ChartViewport,
): Modifier = pointerInput(state, viewport) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset -> state.moveTo(viewport.tOf(offset.x)) },
        onDragEnd = { state.clear() },
        onDragCancel = { state.clear() },
        onDrag = { change, _ -> state.moveTo(viewport.tOf(change.position.x)) },
    )
}

/**
 * Tap to plant the crosshair, tap it again to take it away.
 *
 * Used on the full-screen detail, where the line is meant to stay put while you pinch and pan around
 * it. A second tap within [dismissSlopPx] of where it already is reads as "put it away" rather than
 * "move it a few pixels"; anywhere else moves it.
 *
 * Drag and pinch are untouched — `detectTapGestures` only claims a pointer that went down and up
 * without travelling, so the pan/zoom modifier beneath still sees everything else.
 */
fun Modifier.crosshairTapInput(
    state: CrosshairState,
    viewport: ChartViewport,
    dismissSlopPx: Float = 28f,
): Modifier = pointerInput(state, viewport) {
    detectTapGestures { offset ->
        val t = viewport.tOf(offset.x)
        val current = state.tMs
        if (current != null && abs(viewport.xOf(current) - offset.x) < dismissSlopPx) {
            state.clear()
        } else {
            state.moveTo(t)
        }
    }
}

/**
 * The sample nearest a timestamp, or null when nothing is close enough to be worth showing.
 *
 * [toleranceMs] stops a readout being invented across a gap: with the crosshair parked in the middle
 * of a four-hour hole, the honest answer is "nothing here", not the value from either edge. It
 * defaults to a generous multiple of the metric's own cadence so a normal series always answers.
 */
fun nearestSample(points: List<ChartPoint>, tMs: Long, toleranceMs: Long): ChartPoint? {
    if (points.isEmpty()) return null
    // The series is time-ordered, so binary-search the insertion point and check its two neighbours.
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].tMs < tMs) lo = mid + 1 else hi = mid
    }
    val candidates = listOfNotNull(
        points.getOrNull(lo - 1),
        points.getOrNull(lo),
    )
    return candidates.minByOrNull { abs(it.tMs - tMs) }?.takeIf { abs(it.tMs - tMs) <= toleranceMs }
}

/** The sleep stage in force at a timestamp, or null outside any session. */
fun stageAt(runs: List<SleepRun>, tMs: Long): SleepRun? =
    runs.firstOrNull { tMs >= it.startMs && tMs < it.endMs }
