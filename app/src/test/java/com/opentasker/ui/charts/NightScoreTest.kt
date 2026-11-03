package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The night score — [NightScore]. */
class NightScoreTest {

    private fun all(step: Int?) = NightScore.Part.entries.associateWith { step }

    @Test
    fun `the defaults sum to a hundred`() {
        assertEquals(100, NightScore.DEFAULTS.values.sum())
    }

    @Test
    fun `a row of one step scores that step and is painted it`() {
        for (step in 1..5) {
            val s = NightScore.score(all(step), NightScore.DEFAULTS)
            assertEquals(NightScore.points(step), s.value)
            assertEquals(step, s.step)
            assertTrue(!s.partial)
        }
    }

    @Test
    fun `an empty cell is left out and marked, never scored as bad`() {
        val steps = all(2) + (NightScore.Part.HRV to null)
        val s = NightScore.score(steps, NightScore.DEFAULTS)
        assertEquals(75, s.value)
        assertTrue(s.partial)
        assertEquals(listOf(NightScore.Part.HRV), s.missing.map { it.part })
    }

    @Test
    fun `too little measured gives no score at all`() {
        val steps = all(null) + (NightScore.Part.SPO2 to 1)
        assertNull(NightScore.score(steps, NightScore.DEFAULTS).value)
    }

    @Test
    fun `a zero weight takes a cell out and an empty zero-weight cell is not partial`() {
        val weights = NightScore.DEFAULTS + (NightScore.Part.BP to 0)
        val s = NightScore.score(all(1) + (NightScore.Part.BP to null), weights)
        assertEquals(100, s.value)
        assertTrue(!s.partial)
    }

    @Test
    fun `the weights decide the mix`() {
        val steps = all(5) + (NightScore.Part.SLEEP to 1)
        val onlySleep = NightScore.Part.entries.associateWith { 0 } + (NightScore.Part.SLEEP to 10)
        assertEquals(100, NightScore.score(steps, onlySleep).value)
        val s = NightScore.score(steps, NightScore.DEFAULTS)
        assertEquals(18, s.value)
        // The contributions add up to the score.
        assertEquals(18.0, s.components.sumOf { s.contribution(it) }, 0.01)
    }

    @Test
    fun `spearman handles ties and direction`() {
        assertEquals(1.0, NightScore.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 30.0, 40.0))!!, 1e-9)
        assertEquals(-1.0, NightScore.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(4.0, 3.0, 2.0, 1.0))!!, 1e-9)
        assertNull(NightScore.spearman(listOf(1.0, 2.0, 3.0), listOf(5.0, 5.0, 5.0)))
        val tied = NightScore.spearman(listOf(1.0, 1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0, 4.0))!!
        assertTrue(tied > 0.9 && tied < 1.0)
    }

    @Test
    fun `the interval brackets the coefficient and narrows with nights`() {
        val small = NightScore.fit((1..12).map { it.toDouble() to (it % 5).toDouble() })!!
        val large = NightScore.fit((1..120).map { it.toDouble() to (it % 5 + it / 10).toDouble() })!!
        assertTrue(small.lo <= small.rho && small.rho <= small.hi)
        assertTrue(large.hi - large.lo < small.hi - small.lo)
        assertNull(NightScore.fit((1..5).map { it.toDouble() to it.toDouble() }))
    }

    @Test
    fun `the rolling median ignores gaps and waits for enough nights`() {
        val m = NightScore.rollingMedian(listOf(50, null, 70, 90, 10, 60, 80), 7)
        assertNull(m[0])
        assertEquals(65.0, m[6]!!, 1e-9)
    }
}
