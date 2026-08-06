package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Does a chart inside a scrolling list get its pinch and its horizontal pan **without** stealing the
 * list's vertical scroll?
 *
 * This is the one item the charts hand-off flagged as genuinely uncertain and wanted answered on a
 * device before the power views were designed, because the documented fallback — pinch-only
 * `transformable` plus a separate horizontal `draggable` — changes the layout.
 *
 * It is an instrumented test rather than a screen to poke at because the question is not how the
 * gesture *feels*; it is where each touch sequence is routed, which is a fact and can be asserted.
 * Every gesture below is a real touch stream through Compose's own arbitration.
 */
class ChartGestureInteropTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recorder {
        var zoom = 1f
        var pan = Offset.Zero
        var panEvents = 0
        fun onZoom(z: Float) { zoom *= z }
        fun onPan(p: Offset) { pan += p; panEvents++ }
    }

    /** A chart-shaped box halfway down a list that is much taller than the screen. */
    @Composable
    private fun Harness(
        recorder: Recorder,
        listState: LazyListState,
        gestures: Modifier = rememberChartGestureModifier(
            onZoom = recorder::onZoom,
            onPan = recorder::onPan,
        ),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(LIST)) {
            items((0 until 40).toList()) { index ->
                if (index == CHART_ROW) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(Color.DarkGray)
                            .then(gestures)
                            .testTag(CHART),
                    ) { Text("chart") }
                } else {
                    Box(Modifier.fillMaxWidth().height(80.dp)) { Text("row $index") }
                }
            }
        }
    }

    private fun scrolled(state: LazyListState) =
        state.firstVisibleItemIndex * 100_000 + state.firstVisibleItemScrollOffset

    // ------------------------------------------------------------------------------------------

    @Test
    fun aVerticalDragOnTheChartScrollsTheListAndIsNotClaimedAsPan() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Harness(recorder, state)
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 400f), durationMillis = 300)
        }
        rule.waitForIdle()

        assertTrue(
            "a vertical drag over the chart must reach the LazyColumn — it did not scroll at all",
            scrolled(state) > 0,
        )
        assertEquals(
            "the chart must not claim a vertical drag as pan (got ${recorder.pan})",
            0, recorder.panEvents,
        )
    }

    @Test
    fun aHorizontalDragOnTheChartPansItAndLeavesTheListStill() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Harness(recorder, state)
        }
        val before = scrolled(state)
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(start = center, end = center.copy(x = center.x - 400f), durationMillis = 300)
        }
        rule.waitForIdle()

        assertTrue("the chart must receive a horizontal drag as pan", recorder.panEvents > 0)
        assertTrue(
            "the pan must be horizontal, not a diagonal leak (got ${recorder.pan})",
            abs(recorder.pan.x) > abs(recorder.pan.y),
        )
        assertEquals("the list must not scroll while the chart is panned", before, scrolled(state))
    }

    @Test
    fun aPinchOnTheChartZoomsItRegardlessOfTheCanPanPredicate() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Harness(recorder, state)
        }
        val before = scrolled(state)
        rule.onNodeWithTag(CHART).performTouchInput {
            pinch(
                start0 = center + Offset(-60f, 0f), end0 = center + Offset(-220f, 0f),
                start1 = center + Offset(60f, 0f), end1 = center + Offset(220f, 0f),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()

        assertTrue(
            "a two-finger spread must zoom the chart (zoom=${recorder.zoom})",
            recorder.zoom > 1.2f,
        )
        assertEquals("a pinch must not scroll the list", before, scrolled(state))
    }

    /**
     * A pinch whose fingers travel vertically. This is the case `canPan` could plausibly break: the
     * predicate rejects vertical *pans*, and if it were consulted for zoom as well, a vertical
     * spread would silently do nothing.
     */
    @Test
    fun aVerticalPinchStillZooms() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Harness(recorder, state)
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            pinch(
                start0 = center + Offset(0f, -40f), end0 = center + Offset(0f, -100f),
                start1 = center + Offset(0f, 40f), end1 = center + Offset(0f, 100f),
                durationMillis = 400,
            )
        }
        rule.waitForIdle()

        assertTrue(
            "zoom must not depend on the pan axis (zoom=${recorder.zoom})",
            recorder.zoom > 1.05f,
        )
    }

    /**
     * The control. Without `canPan`, the same harness must FAIL the same way the charts would.
     *
     * A suite of green tests proves nothing on its own — `swipe()` could be producing touch streams
     * too gentle to trigger anything, and every assertion above would still pass. So here is a plain
     * `transformable` with no predicate, driven by the identical gesture: it swallows the vertical
     * drag and the list stays put. That is the bug `canPan` exists to prevent, reproduced on demand.
     *
     * If this test ever starts passing, the vertical-drag test above has stopped meaning anything.
     */
    @Test
    fun withoutCanPanTheChartSwallowsTheScroll_whichIsWhyThePredicateExists() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            val naive = rememberNaiveTransformable(recorder)
            Harness(recorder, state, gestures = naive)
        }
        rule.onNodeWithTag(CHART).performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 400f), durationMillis = 300)
        }
        rule.waitForIdle()

        assertEquals(
            "a bare transformable is expected to eat the scroll — if it no longer does, the " +
                "positive test above has lost its teeth and canPan is no longer being proven",
            0, scrolled(state),
        )
        assertTrue("a bare transformable claims the vertical drag as pan", recorder.panEvents > 0)
    }

    @Composable
    private fun rememberNaiveTransformable(recorder: Recorder): Modifier {
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            if (zoomChange != 1f) recorder.onZoom(zoomChange)
            if (panChange != Offset.Zero) recorder.onPan(panChange)
        }
        return Modifier.transformable(state)
    }

    /** Off the chart, nothing changes: the list scrolls and the chart hears nothing. */
    @Test
    fun aDragElsewhereInTheListNeverReachesTheChart() {
        val recorder = Recorder()
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Harness(recorder, state)
        }
        rule.onNodeWithTag(LIST).performTouchInput {
            swipe(start = topCenter + Offset(0f, 40f), end = topCenter + Offset(0f, 400f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals("the chart must not see gestures aimed at the list", 0, recorder.panEvents)
    }

    private companion object {
        const val LIST = "gesture-list"
        const val CHART = "gesture-chart"
        const val CHART_ROW = 3
    }
}
