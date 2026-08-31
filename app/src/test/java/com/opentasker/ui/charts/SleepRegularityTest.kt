package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sleep Regularity Index, against its definition and its published bands.
 *
 * The point of the metric is that it separates people whom *duration* cannot tell apart, so the
 * central test builds two schedules with identical total sleep and checks they score differently.
 */
class SleepRegularityTest {

    private val minute = SleepRegularity.MINUTE_MS
    private val day = 24 * 60L

    /** A run of asleep minutes, in minute indices. */
    private fun night(startMinute: Long, minutes: Long): List<Long> =
        (0 until minutes).map { startMinute + it }

    @Test
    fun `an identical schedule every night scores 100`() {
        val asleep = HashSet<Long>()
        // Asleep 23:00 → 07:00, ten nights running.
        for (d in 0 until 10) asleep += night(d * day + 23 * 60, 8 * 60)
        // The window starts at the FIRST onset, as the production path does. Starting at midnight
        // instead would compare day 0's morning — before any night was recorded — against day 1's
        // sleep, and score a perfectly regular sleeper 93.5 for an edge effect.
        val sri = SleepRegularity.index(asleep, 23 * 60, 10 * day + 7 * 60)!!
        assertEquals(100.0, sri, 0.01)
        assertEquals(RegularityBand.VERY_REGULAR, SleepRegularity.band(sri))
    }

    /**
     * The finding the metric exists for: same total sleep, very different regularity.
     *
     * Both schedules average eight hours a night. One keeps the same clock times; the other shifts by
     * six hours every other night. Duration cannot distinguish them and this must.
     */
    @Test
    fun `the same total sleep can be regular or irregular`() {
        val regular = HashSet<Long>()
        val scattered = HashSet<Long>()
        for (d in 0 until 12) {
            regular += night(d * day + 23 * 60, 8 * 60)
            val shift = if (d % 2 == 0) 0L else 6 * 60L
            scattered += night(d * day + 23 * 60 + shift, 8 * 60)
        }
        val a = SleepRegularity.index(regular, 23 * 60, 12 * day + 7 * 60)!!
        val b = SleepRegularity.index(scattered, 23 * 60, 12 * day + 7 * 60)!!
        assertEquals(100.0, a, 0.01)
        assertTrue("a six-hour swing must cost a great deal", b < 60.0)
        assertEquals(RegularityBand.IRREGULAR, SleepRegularity.band(b))
    }

    @Test
    fun `a week is the floor, below it there is no number`() {
        val asleep = HashSet<Long>()
        for (d in 0 until 4) asleep += night(d * day + 23 * 60, 8 * 60)
        assertNull(SleepRegularity.index(asleep, 0, 4 * day))
        assertTrue(SleepRegularity.index(asleep, 0, 8 * day) != null)
    }

    /**
     * Missing data must read as awake, not as absent.
     *
     * The band records sleep; an absence of a sleep record is an absence of sleep. Dropping unknown
     * minutes instead would score a gap in the data as a perfectly regular stretch — flattering
     * exactly the weeks where the band was off the wrist.
     */
    @Test
    fun `a missing night costs regularity rather than being ignored`() {
        val full = HashSet<Long>()
        val missing = HashSet<Long>()
        for (d in 0 until 10) {
            full += night(d * day + 23 * 60, 8 * 60)
            if (d != 5) missing += night(d * day + 23 * 60, 8 * 60)
        }
        val a = SleepRegularity.index(full, 0, 10 * day)!!
        val b = SleepRegularity.index(missing, 0, 10 * day)!!
        assertTrue("the gap has to show", b < a)
    }

    @Test
    fun `the bands are the UK Biobank quintile boundaries`() {
        assertEquals(RegularityBand.IRREGULAR, SleepRegularity.band(70.0))
        assertEquals(RegularityBand.MIDDLING, SleepRegularity.band(75.0))
        assertEquals(RegularityBand.REGULAR, SleepRegularity.band(80.0))
        assertEquals(RegularityBand.VERY_REGULAR, SleepRegularity.band(90.0))
    }

    /** Built from real session shapes, so the plumbing from stage runs is exercised too. */
    @Test
    fun `it builds from stitched sessions`() {
        val sessions = (0 until 9).map { d ->
            val start = (d * day + 23 * 60) * minute
            SleepSession(
                startMs = start,
                endMs = start + 8 * 60 * minute,
                runs = listOf(SleepRun(start, start + 8 * 60 * minute, '2')),
            )
        }
        val sri = SleepRegularity.of(sessions)!!
        assertEquals(100.0, sri, 0.01)
    }
}
