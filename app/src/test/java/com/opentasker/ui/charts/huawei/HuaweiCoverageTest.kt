package com.opentasker.ui.charts.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that will be used to justify rewriting a gate value, so it is pinned before anyone
 * relies on it.
 */
class HuaweiCoverageTest {

    private fun every(n: Int, step: Long, from: Long = 1_000L) =
        (0 until n).map { from + it * step }

    @Test
    fun `no samples reports nothing rather than zero`() {
        // "Not measured" and "measured as nothing" are different claims, and a cadence of 0 would
        // be read as a measurement.
        val c = HuaweiCoverage.from(HuaweiKeys.HEART_RATE, emptyList())
        assertEquals(0, c.samples)
        assertNull(c.p50GapSec)
        assertNull(c.observedCadenceSec)
        assertNull(c.spanSeconds)
        assertNull(c.density)
    }

    @Test
    fun `one sample has no gaps, because a gap needs two readings`() {
        val c = HuaweiCoverage.from(HuaweiKeys.SPO2, listOf(5_000L))
        assertEquals(1, c.samples)
        assertEquals(5_000L, c.firstSeconds)
        assertEquals(5_000L, c.lastSeconds)
        assertNull(c.p50GapSec)
        assertNull(c.longestGapSec)
    }

    @Test
    fun `a steady series reports its own interval as the cadence`() {
        val c = HuaweiCoverage.from(HuaweiKeys.STEPS, every(60, 60L))
        assertEquals(60, c.observedCadenceSec)
        assertEquals(60, c.p90GapSec)
        assertEquals(60, c.longestGapSec)
        assertEquals(1.0, c.density!!, 0.001)
    }

    @Test
    fun `one long absence does not move the cadence, but does show in the tail`() {
        // The band off the wrist for an hour must not turn into "this metric samples hourly".
        // That is the whole reason the reported cadence is a median.
        val times = every(30, 60L) + listOf(1_000L + 29 * 60L + 3_600L)
        val c = HuaweiCoverage.from(HuaweiKeys.HEART_RATE, times)
        assertEquals("the median must ignore the outage", 60, c.observedCadenceSec)
        assertEquals("the outage must still be visible", 3_600, c.longestGapSec)
        assertTrue("density must fall, because a stretch really was missed", c.density!! < 0.6)
    }

    @Test
    fun `unsorted input is sorted rather than trusted`() {
        // Handed over unsorted, a naive implementation produces negative gaps and then a perfectly
        // plausible-looking cadence computed from them.
        val ordered = HuaweiCoverage.from(HuaweiKeys.SPO2, every(20, 120L))
        val shuffled = HuaweiCoverage.from(HuaweiKeys.SPO2, every(20, 120L).reversed())
        assertEquals(ordered.observedCadenceSec, shuffled.observedCadenceSec)
        assertEquals(ordered.longestGapSec, shuffled.longestGapSec)
        assertEquals(ordered.firstSeconds, shuffled.firstSeconds)
        assertTrue("no gap may be negative", (shuffled.longestGapSec ?: 0) >= 0)
    }

    @Test
    fun `percentiles are observed gaps, never interpolated between them`() {
        // A value between two real gaps is a gap that never happened.
        val times = listOf(0L, 10L, 20L, 30L, 40L, 340L)   // gaps 10,10,10,10,300
        val c = HuaweiCoverage.from(HuaweiKeys.STEPS, times)
        assertEquals(10, c.p50GapSec)
        assertEquals(300, c.p90GapSec)
        assertEquals(300, c.longestGapSec)
        assertTrue(listOf(10, 300).contains(c.p99GapSec))
    }

    @Test
    fun `density is capped at one, so a dense burst cannot read as over-complete`() {
        val c = HuaweiCoverage.from(HuaweiKeys.HEART_RATE, every(10, 1L))
        assertTrue(c.density!! <= 1.0)
    }
}
