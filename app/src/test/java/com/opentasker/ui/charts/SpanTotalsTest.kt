package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind a marked span. The gesture that produces the span is Compose's
 * `detectDragGesturesAfterLongPress`, already proven on this device by the dashboard crosshair; what
 * is worth pinning here is the sum, because a total that quietly counts the wrong samples looks
 * entirely plausible on screen.
 */
class SpanTotalsTest {

    private fun points(vararg pairs: Pair<Long, Double>) = pairs.map { ChartPoint(it.first, it.second) }

    @Test
    fun `sums only the samples inside the span`() {
        val series = points(
            1_000L to 10.0,
            2_000L to 20.0,
            3_000L to 30.0,
            4_000L to 40.0,
        )
        val totals = SpanTotals.of(series, 2_000L, 3_000L)!!

        assertEquals(2, totals.count)
        assertEquals(50.0, totals.sum, 0.0001)
        assertEquals(25.0, totals.mean, 0.0001)
        assertEquals(20.0, totals.min, 0.0001)
        assertEquals(30.0, totals.max, 0.0001)
    }

    @Test
    fun `both ends of the span are inclusive`() {
        // The edges are where an off-by-one hides: a walk's first and last minute must be in the total.
        val series = points(1_000L to 5.0, 2_000L to 7.0)
        val totals = SpanTotals.of(series, 1_000L, 2_000L)!!

        assertEquals(2, totals.count)
        assertEquals(12.0, totals.sum, 0.0001)
    }

    @Test
    fun `a span with no samples in it is null rather than zero`() {
        // A confident "0 steps" over a gap in the record would be a lie; the readout says so instead.
        val series = points(1_000L to 5.0, 9_000L to 5.0)

        assertNull(SpanTotals.of(series, 3_000L, 4_000L))
        assertNull(SpanTotals.of(emptyList(), 0L, 10_000L))
    }

    @Test
    fun `a single sample is a legitimate span`() {
        val totals = SpanTotals.of(points(1_000L to 42.0), 1_000L, 1_000L)!!

        assertEquals(1, totals.count)
        assertEquals(42.0, totals.sum, 0.0001)
        assertEquals(42.0, totals.mean, 0.0001)
    }

    @Test
    fun `state orders the span whichever way the finger went`() {
        val state = SpanSelectionState()
        state.begin(5_000L)
        state.dragTo(2_000L)          // dragged right to left

        assertEquals(2_000L, state.startMs)
        assertEquals(5_000L, state.endMs)
        assertEquals(true, state.active)
    }

    @Test
    fun `clearing the state takes the span away`() {
        val state = SpanSelectionState()
        state.begin(1_000L)
        state.dragTo(2_000L)
        state.clear()

        assertNull(state.startMs)
        assertNull(state.endMs)
        assertEquals(false, state.active)
    }

    @Test
    fun `dragging without beginning does nothing`() {
        val state = SpanSelectionState()
        state.dragTo(9_000L)

        assertNull(state.startMs)
        assertEquals(false, state.active)
    }
}
