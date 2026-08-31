package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the crosshair reads out, and — more importantly — what it refuses to.
 *
 * The gesture is Compose and belongs in an instrumented test; the *lookup* is arithmetic and belongs
 * here. Its one dangerous behaviour is answering across a gap: park the line in the middle of a
 * four-hour hole and the nearest sample is still two hours away, and printing it would state a
 * reading for a time when the band was not measuring — the exact claim the gap tint exists to deny.
 */
class ChartCrosshairTest {

    private val minute = 60_000L
    private val series = (0..20).map { ChartPoint(it * minute, 60.0 + it) }

    @Test
    fun `it finds the sample either side of the touch`() {
        assertEquals(60.0, nearestSample(series, 0L, minute)!!.value, 0.0)
        assertEquals(65.0, nearestSample(series, 5 * minute + 100, minute)!!.value, 0.0)
        // Nearer the later of two neighbours.
        assertEquals(66.0, nearestSample(series, 5 * minute + 40_000, minute)!!.value, 0.0)
    }

    @Test
    fun `it answers at both ends of the series`() {
        assertEquals(60.0, nearestSample(series, -minute / 2, minute)!!.value, 0.0)
        assertEquals(80.0, nearestSample(series, 20 * minute + minute / 2, minute)!!.value, 0.0)
    }

    /** The one that matters: no invented readout in the middle of a hole. */
    @Test
    fun `it refuses to answer across a gap wider than the tolerance`() {
        val withHole = series.filter { it.tMs < 5 * minute || it.tMs > 14 * minute }
        assertNull(nearestSample(withHole, 10 * minute, 2 * minute))
        // …and answers again as soon as the touch is back near real data: the last sample before the
        // hole is at 4 min, which is 64 bpm.
        assertEquals(64.0, nearestSample(withHole, 5 * minute, 2 * minute)!!.value, 0.0)
    }

    @Test
    fun `an empty series answers nothing rather than throwing`() {
        assertNull(nearestSample(emptyList(), 0L, minute))
    }

    @Test
    fun `a single-sample series still answers inside the tolerance`() {
        val one = listOf(ChartPoint(10 * minute, 72.0))
        assertEquals(72.0, nearestSample(one, 10 * minute + 1000, minute)!!.value, 0.0)
        assertNull(nearestSample(one, 30 * minute, minute))
    }

    @Test
    fun `the sleep stage at an instant is the run containing it`() {
        val runs = listOf(
            SleepRun(0, 10 * minute, '1'),
            SleepRun(10 * minute, 25 * minute, '2'),
            SleepRun(25 * minute, 30 * minute, '3'),
        )
        assertEquals('1', stageAt(runs, 5 * minute)!!.code)
        assertEquals('2', stageAt(runs, 10 * minute)!!.code)   // start is inclusive
        assertEquals('3', stageAt(runs, 29 * minute)!!.code)
        assertNull("a run's end is exclusive", stageAt(runs, 30 * minute))
        assertNull("nothing before the night started", stageAt(runs, -minute))
    }

    @Test
    fun `no stage outside any session`() {
        assertNull(stageAt(emptyList(), 0L))
    }
}
