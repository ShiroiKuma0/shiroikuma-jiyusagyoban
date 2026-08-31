package com.opentasker.ui.charts

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * The one gesture question the charts have to answer before the power views are designed.
 *
 * A chart wants **pinch to zoom** and **horizontal drag to pan through time**, while living inside a
 * vertically scrolling list that wants **vertical drag to scroll**. Those two claims overlap: a
 * `transformable` consumes pan on both axes by default, and the moment it does, the enclosing
 * `LazyColumn` never sees the drag — the page stops scrolling wherever a chart happens to be under
 * the finger.
 *
 * `canPan` resolves it. Compose consults it *before* the transformable claims a pan, so refusing the
 * vertical ones lets them fall through to the list while the horizontal ones stay with the chart.
 * Pinch is unaffected either way: a two-finger zoom is not a pan and never reaches this predicate.
 *
 * The ratio is the whole design. At 1.0 a drag a hair off vertical is claimed by the chart and the
 * list feels sticky; too high and a deliberately horizontal drag is handed to the list instead. 1.4
 * means "clearly more horizontal than vertical" — about 35° either side of the horizontal — which is
 * comfortably past the angle a finger wanders through while scrolling.
 *
 * Whether this actually works is not a matter of opinion: `ChartGestureInteropTest` drives real touch
 * events through it on a device and asserts where each one landed.
 */
@Composable
fun rememberChartGestureModifier(
    onZoom: (Float) -> Unit,
    onPan: (Offset) -> Unit,
    panRatio: Float = CHART_PAN_RATIO,
    enabled: Boolean = true,
): Modifier {
    // The callbacks are read at gesture time, not at composition time, so a recomposition between
    // touch-down and touch-up cannot leave the gesture reporting into a stale lambda.
    val zoom by rememberUpdatedState(onZoom)
    val pan by rememberUpdatedState(onPan)
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        if (zoomChange != 1f) zoom(zoomChange)
        if (panChange != Offset.Zero) pan(panChange)
    }
    val canPan = remember(panRatio) {
        { offset: Offset -> abs(offset.x) > abs(offset.y) * panRatio }
    }
    return Modifier.transformable(state = state, canPan = canPan, enabled = enabled)
}

/** Clearly-more-horizontal-than-vertical: about 35° either side of the horizontal. */
const val CHART_PAN_RATIO = 1.4f
