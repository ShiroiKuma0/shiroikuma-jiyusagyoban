package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published reference bands, boundary by boundary.
 *
 * Every cut point below traces to a paper named in [RecoveryReference]'s KDoc. They are here because
 * a band that grades 白い熊's nights against the literature has to keep matching the literature: an
 * edit that moves 7 h or 60 bpm should break a test, not quietly re-score a year of history.
 *
 * The step numbers were inverted on 2026-08-12 when the scale became best-first. **The cut points did
 * not move** — 7 h is still 7 h — which is the thing these tests are actually guarding.
 */
class RecoveryReferenceTest {

    private fun h(hours: Double) = hours * 60.0

    // --- sleep -------------------------------------------------------------------------------

    @Test
    fun `the NSF recommended window is the top step`() {
        assertEquals(1, RecoveryReference.sleepStep(h(7.0)))
        assertEquals(1, RecoveryReference.sleepStep(h(8.0)))
        assertEquals(1, RecoveryReference.sleepStep(h(9.0)))
    }

    /**
     * Short and long are NOT symmetric. 6–7 h and 9–10 h are the same NSF category, but the AASM line
     * is "7 or more hours", so falling short of it ranks below overshooting it.
     */
    @Test
    fun `falling short ranks below overshooting by the same amount`() {
        assertEquals(3, RecoveryReference.sleepStep(h(6.5)))
        assertEquals(2, RecoveryReference.sleepStep(h(9.5)))
        // 1 is the best step, so ranking below means a HIGHER number.
        assertTrue(RecoveryReference.sleepStep(h(6.5)) > RecoveryReference.sleepStep(h(9.5)))
    }

    @Test
    fun `the boundaries are exactly where the consensus puts them`() {
        assertEquals("7h is recommended, not below it", 1, RecoveryReference.sleepStep(h(7.0)))
        assertEquals("just under 7h is not", 3, RecoveryReference.sleepStep(h(7.0) - 1))
        assertEquals("9h is still recommended", 1, RecoveryReference.sleepStep(h(9.0)))
        assertEquals("just over 9h is not", 2, RecoveryReference.sleepStep(h(9.0) + 1))
        assertEquals("6h enters may-be-appropriate", 3, RecoveryReference.sleepStep(h(6.0)))
        assertEquals("just under 6h leaves it", 4, RecoveryReference.sleepStep(h(6.0) - 1))
        assertEquals("10h is the top of may-be-appropriate", 2, RecoveryReference.sleepStep(h(10.0)))
        assertEquals("just over 10h leaves it", 4, RecoveryReference.sleepStep(h(10.0) + 1))
    }

    @Test
    fun `the extremes are the bottom step on both sides`() {
        assertEquals(5, RecoveryReference.sleepStep(h(4.0)))
        assertEquals(5, RecoveryReference.sleepStep(h(12.0)))
        assertEquals(5, RecoveryReference.sleepStep(0.0))
    }

    /** 白い熊's own nights, as a sanity read of the whole mapping. */
    @Test
    fun `the recorded nights land where they should`() {
        assertEquals("6h10 is short of the recommendation", 3, RecoveryReference.sleepStep(370.0))
        assertEquals("5h51 is below may-be-appropriate", 4, RecoveryReference.sleepStep(351.0))
        assertEquals("7h40 meets it", 1, RecoveryReference.sleepStep(460.0))
        assertEquals("8h32 meets it", 1, RecoveryReference.sleepStep(512.0))
        assertEquals("9h18 overshoots it", 2, RecoveryReference.sleepStep(558.0))
    }

    // --- nocturnal heart rate ----------------------------------------------------------------

    @Test
    fun `the resting-rate decades map one step each`() {
        assertEquals(1, RecoveryReference.nocturnalHrStep(48.0))
        assertEquals(2, RecoveryReference.nocturnalHrStep(55.0))
        assertEquals(3, RecoveryReference.nocturnalHrStep(64.0))
        assertEquals(4, RecoveryReference.nocturnalHrStep(75.0))
        assertEquals(5, RecoveryReference.nocturnalHrStep(88.0))
    }

    @Test
    fun `each decade boundary belongs to the better side below it`() {
        assertEquals(1, RecoveryReference.nocturnalHrStep(49.9))
        assertEquals(2, RecoveryReference.nocturnalHrStep(50.0))
        assertEquals(2, RecoveryReference.nocturnalHrStep(59.9))
        assertEquals(3, RecoveryReference.nocturnalHrStep(60.0))
        assertEquals(3, RecoveryReference.nocturnalHrStep(69.9))
        assertEquals(4, RecoveryReference.nocturnalHrStep(70.0))
        assertEquals(4, RecoveryReference.nocturnalHrStep(79.9))
        assertEquals(5, RecoveryReference.nocturnalHrStep(80.0))
    }

    /** Monotone by construction, because the risk it stands in for is monotone across these cuts. */
    @Test
    fun `a higher heart rate never scores better`() {
        // Best-first: the step may only climb as the rate does, never fall back toward 1.
        var previous = 0
        for (bpm in 40..110) {
            val step = RecoveryReference.nocturnalHrStep(bpm.toDouble())
            assertTrue("$bpm scored better than the rate below it", step >= previous)
            previous = step
        }
    }
}
