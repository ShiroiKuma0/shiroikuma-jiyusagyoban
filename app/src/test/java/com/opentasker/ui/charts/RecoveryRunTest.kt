package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consecutive-night run, and the HR swing — the two things 白い熊 asked for on 2026-09-12.
 *
 * Both are pinned to 白い熊's own 21 nights, because both were CHOSEN on that data rather than from
 * a paper, and a measure chosen on data has to be tested against it or the choice is unrecorded.
 */
class RecoveryRunTest {

    /**
     * Nightly HF power, oldest first, for every night on record to 2026-09-12.
     *
     * The series [Recovery.runAbove] is applied to. Medians of `rri_f8` over each night, read
     * straight out of the exported archive.
     */
    private val hf = listOf<Double?>(
        null, 43.0, 30.0, 27.0, 51.0, 45.0, 21.0, 9.0, 10.0, 29.0, 36.0, 28.0,
        58.0, 27.0, 40.0, 39.0, 64.0, 37.0, 48.0, 55.0, 59.0, 53.0,
    )

    /** The same nights' RMSSD, which lost the comparison — see [hfIsTheMoreSpecificSeries]. */
    private val rmssd = listOf<Double?>(
        null, 24.0, 21.7, 19.9, 25.5, 25.2, 20.2, 13.0, 10.6, 21.9, 24.7, 24.6,
        34.2, 21.7, 25.4, 26.3, 33.7, 20.3, 25.6, 30.9, 27.3, 27.0,
    )

    /** Run length at each night, for counting how often a threshold would have fired. */
    private fun runsOver(series: List<Double?>): List<Int> =
        series.indices.map { Recovery.runAbove(series.subList(0, it + 1)) }

    @Test
    fun `the record's one run of four ends on the last night`() {
        assertEquals(Recovery.HRV_RUN_NIGHTS, Recovery.runAbove(hf))
    }

    /**
     * Exactly one run of four in 21 nights, on HF. That uniqueness IS the threshold's justification.
     */
    @Test
    fun `a run of four occurs once in the record`() {
        val hits = runsOver(hf).count { it == Recovery.HRV_RUN_NIGHTS }
        assertEquals("HF runs of four: $hits", 1, hits)
    }

    /**
     * HF was chosen over RMSSD by measurement, not by preference.
     *
     * RMSSD reaches four twice over the same nights — one of them an unremarkable night — so it
     * would double the alarm rate to catch the same episode. If a future change makes RMSSD the
     * series, this is the test that should have stopped it.
     */
    @Test
    fun hfIsTheMoreSpecificSeries() {
        val hfHits = runsOver(hf).count { it == Recovery.HRV_RUN_NIGHTS }
        val rmssdHits = runsOver(rmssd).count { it == Recovery.HRV_RUN_NIGHTS }
        assertTrue("HF $hfHits vs RMSSD $rmssdHits", hfHits < rmssdHits)
    }

    /**
     * It CONFIRMS rather than predicts, and the test says so out loud.
     *
     * The run stands at 3 on the first morning 白い熊 felt ill and 4 on the second. Nothing here
     * bought a day's notice. Asserted because the temptation to describe this as an early warning
     * will outlive the memory of measuring it.
     */
    @Test
    fun `the run had not reached its threshold before the illness began`() {
        // index 20 is 2026-09-11, the first bad day; 21 is 2026-09-12.
        assertEquals(3, Recovery.runAbove(hf.subList(0, 21)))
        assertEquals(4, Recovery.runAbove(hf.subList(0, 22)))
    }

    /** A missing night breaks the run rather than being skipped over. */
    @Test
    fun `a gap breaks the run`() {
        val base = listOf<Double?>(10.0, 10.0, 10.0, 10.0, 20.0, 21.0, 22.0, 23.0)
        assertEquals(4, Recovery.runAbove(base))
        val gapped = base.toMutableList().also { it[6] = null }
        assertTrue("a null must not be stepped over", Recovery.runAbove(gapped) < 4)
    }

    /** Each night is judged against the nights before IT — not against the whole series. */
    @Test
    fun `a run is not hidden by the baseline it raises`() {
        // Four quiet nights then six steadily elevated ones. Judged against the whole series the
        // later nights sit near the middle and the run would read short; judged against their own
        // predecessors it reads as the six it is.
        val v = listOf<Double?>(10.0, 11.0, 10.0, 11.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0)
        assertEquals(6, Recovery.runAbove(v))
    }

    @Test
    fun `no run when the last night is not above its baseline`() {
        assertEquals(0, Recovery.runAbove(listOf(10.0, 11.0, 10.0, 11.0, 20.0, 5.0)))
    }

    /** Too little history to know a baseline is not a run of zero-length — it is no run. */
    @Test
    fun `a short series yields no run`() {
        assertEquals(0, Recovery.runAbove(listOf(10.0, 20.0, 30.0)))
    }

    // ---- the HR swing ---------------------------------------------------------------------

    /** 白い熊's nightly HR swing, oldest first — max minus min of the twelve-bin curve. */
    private val swing = listOf(
        16.0, 12.5, 14.0, 10.0, 12.0, 6.5, 9.5, 9.5, 9.5, 14.5, 16.5,
        14.0, 14.0, 8.5, 12.5, 16.0, 17.0, 11.0, 15.0, 13.5, 6.0,
    )

    /**
     * The flattest night on record is the one it was built to see.
     *
     * 6.0 bpm on 2026-09-11 against a trailing median of 13.5 — the smallest of the 21. This is the
     * measure that survived; the dip it replaced read 7.2 against a 2.8 median on the same night,
     * which is to say it read BETTER than average on the night in question.
     */
    @Test
    fun `the flat night is the smallest swing in the record`() {
        assertEquals(6.0, swing.last(), 1e-9)
        assertEquals(6.0, swing.min(), 1e-9)
    }

    /** And it lands outside its own usual band, which is what makes it visible on the card. */
    @Test
    fun `the flat night leaves its usual band`() {
        val prior = swing.dropLast(1).takeLast(7)
        val r = Recovery.band(
            RecoveryMarker.HR_SWING, swing.last(), prior, Recovery.HR_MEANINGFUL_BPM,
            RecoveryConfidence.ESTABLISHED, counted = false,
        )
        assertEquals(RecoveryBand.LOW, r.band)
        assertTrue("a small swing is the worse direction", (r.scaleStep ?: 3) >= 4)
    }

    /** An ordinary night stays inside it, so the row is not permanently coloured. */
    @Test
    fun `an ordinary swing stays usual`() {
        val prior = swing.subList(10, 17)
        val r = Recovery.band(
            RecoveryMarker.HR_SWING, 14.0, prior, Recovery.HR_MEANINGFUL_BPM,
            RecoveryConfidence.ESTABLISHED, counted = false,
        )
        assertEquals(RecoveryBand.USUAL, r.band)
    }

    /** The swing never reaches the counting rule — it is display-only, like the rest of the strip. */
    @Test
    fun `the swing is never counted`() {
        val r = Recovery.band(
            RecoveryMarker.HR_SWING, 6.0, swing.take(7), Recovery.HR_MEANINGFUL_BPM,
            RecoveryConfidence.ESTABLISHED, counted = false,
        )
        assertTrue(!r.counted)
        assertTrue("and never adverse, whatever it does", !r.adverse)
    }
}
