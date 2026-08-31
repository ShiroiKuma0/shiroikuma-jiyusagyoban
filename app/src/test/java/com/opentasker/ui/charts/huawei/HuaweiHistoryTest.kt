package com.opentasker.ui.charts.huawei

import com.opentasker.ui.charts.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The historical prefix, which is only honest as long as the two bands never overlap.
 *
 * 白い熊's rule, 2026-08-23: the Hume band fills the era BEFORE the Huawei band existed and stops
 * there; from the cutover onward the Huawei band is alone. These tests defend that boundary and the
 * thinning that keeps the older era from looking better-measured than it was.
 */
class HuaweiHistoryTest {

    private fun points(vararg atSeconds: Long) =
        atSeconds.map { ChartPoint(it * 1000L, 1.0) }

    @Test
    fun `thinning keeps one reading per window, and it is a real one`() {
        val dense = points(0, 10, 20, 30, 61, 65, 130)
        val thinned = HuaweiHistory.thin(dense, 60_000L)
        // 0, 61, 130 — the first in each window. Never an average: every point drawn happened.
        assertEquals(listOf(0L, 61_000L, 130_000L), thinned.map { it.tMs })
        assertTrue("every kept point must come from the input", thinned.all { it in dense })
    }

    @Test
    fun `thinning never invents a point in an empty stretch`() {
        val sparse = points(0, 3_600)
        assertEquals(2, HuaweiHistory.thin(sparse, 60_000L).size)
    }

    @Test
    fun `a step of zero leaves the series untouched rather than dividing by it`() {
        val p = points(0, 1, 2)
        assertEquals(p, HuaweiHistory.thin(p, 0L))
    }

    @Test
    fun `an empty series thins to nothing without complaint`() {
        assertEquals(emptyList<ChartPoint>(), HuaweiHistory.thin(emptyList(), 60_000L))
    }

    @Test
    fun `thinning is idempotent — running it twice changes nothing`() {
        val once = HuaweiHistory.thin(points(0, 10, 70, 80, 140), 60_000L)
        assertEquals(once, HuaweiHistory.thin(once, 60_000L))
    }
}
