package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple's published sleep weights, the peak-cadence measure, and the regime detectors.
 */
class SleepScoreAndRegimeTest {

    // --- the published composition ----------------------------------------------------------------

    @Test
    fun `the weights are Apple's, and they total 100`() {
        assertEquals(100.0, SleepScore.W_DURATION + SleepScore.W_CONSISTENCY + SleepScore.W_INTERRUPTIONS, 0.001)
        assertEquals(50.0, SleepScore.W_DURATION, 0.001)
        assertEquals(30.0, SleepScore.W_CONSISTENCY, 0.001)
        assertEquals(20.0, SleepScore.W_INTERRUPTIONS, 0.001)
        assertEquals(13, SleepScore.CONSISTENCY_NIGHTS)
    }

    @Test
    fun `a long consistent unbroken night scores full marks`() {
        val onsets = List(13) { 23 * 60.0 }
        val b = SleepScore.score(8 * 60.0, 0.0, 23 * 60.0, onsets)
        assertEquals(100, b.total)
        assertEquals(SleepScoreBand.VERY_HIGH, SleepScore.band(b.total))
    }

    /**
     * Consistency is worth 30 points, which is more than most people expect — and is the whole
     * reason to copy Apple's split rather than invent one. Same sleep, three hours later to bed.
     */
    @Test
    fun `going to bed three hours late costs the whole consistency budget`() {
        val onsets = List(13) { 23 * 60.0 }
        val onTime = SleepScore.score(8 * 60.0, 0.0, 23 * 60.0, onsets)
        val late = SleepScore.score(8 * 60.0, 0.0, 2 * 60.0, onsets)
        assertEquals(30.0, onTime.consistency, 0.001)
        assertEquals(0.0, late.consistency, 0.001)
        assertEquals(onTime.total - 30, late.total)
    }

    /**
     * Midnight must not be a cliff.
     *
     * 23:50 and 00:10 are twenty minutes apart. Computed on a linear clock they are 23 h 40 apart,
     * and every night 白い熊 goes to bed either side of midnight would score zero for consistency.
     */
    @Test
    fun `the clock is circular`() {
        assertEquals(20.0, SleepScore.circularDistance(23 * 60.0 + 50, 10.0), 0.001)
        assertEquals(20.0, SleepScore.circularDistance(10.0, 23 * 60.0 + 50), 0.001)
        val onsets = listOf(23 * 60.0 + 50, 10.0, 23 * 60.0 + 55, 5.0)
        val b = SleepScore.score(8 * 60.0, 0.0, 0.0, onsets)
        assertTrue("a midnight sleeper is consistent, not erratic", b.consistency > 25.0)
    }

    @Test
    fun `duration and interruptions move the score the published way`() {
        val onsets = List(13) { 23 * 60.0 }
        val short = SleepScore.score(3.5 * 60, 0.0, 23 * 60.0, onsets)
        assertEquals("half the target is half the duration budget", 25.0, short.duration, 0.5)
        val broken = SleepScore.score(8 * 60.0, 45.0, 23 * 60.0, onsets)
        assertEquals("45 min awake is half the interruption budget", 10.0, broken.interruptions, 0.5)
    }

    @Test
    fun `the bands are Apple's`() {
        assertEquals(SleepScoreBand.VERY_LOW, SleepScore.band(40))
        assertEquals(SleepScoreBand.LOW, SleepScore.band(41))
        assertEquals(SleepScoreBand.OK, SleepScore.band(61))
        assertEquals(SleepScoreBand.HIGH, SleepScore.band(81))
        assertEquals(SleepScoreBand.VERY_HIGH, SleepScore.band(96))
    }

    // --- peak cadence -----------------------------------------------------------------------------

    /**
     * The mean of the 30 highest minutes, NOT the best consecutive half hour. That is the definition
     * the mortality association was measured against.
     */
    @Test
    fun `peak 30 takes the highest minutes wherever they fall`() {
        val scattered = (0 until 200).map { ChartPoint(it * 60_000L, if (it % 7 == 0) 120.0 else 10.0) }
        val peak = RecoverySource.peakCadence(scattered, 30)!!
        // 29 minutes at 120 exist; the 30th slot is a 10.
        assertEquals((29 * 120.0 + 10.0) / 30.0, peak, 0.001)
        assertTrue("well above the population norm of 71", peak > 71.0)
    }

    @Test
    fun `peak cadence is null with no steps at all`() {
        assertNull(RecoverySource.peakCadence(emptyList(), 30))
    }

    // --- regime detection -------------------------------------------------------------------------

    @Test
    fun `a time-zone change is noticed and then stops being news`() {
        val offsets = mapOf(100L to 120, 101L to 120, 102L to 540, 103L to 540)
        val fresh = RecoveryRegime.detect(offsets, todayEpochDay = 104, spo2ByNight = emptyList())
        assertEquals(2, fresh.daysSinceZoneChange)
        assertTrue(fresh.any)

        val stale = RecoveryRegime.detect(offsets, todayEpochDay = 130, spo2ByNight = emptyList())
        assertNull("a rolling baseline has re-converged by then", stale.daysSinceZoneChange)
    }

    @Test
    fun `a steady zone produces no travel note`() {
        val offsets = (100L..110L).associateWith { 120 }
        assertNull(RecoveryRegime.detect(offsets, 111, emptyList()).daysSinceZoneChange)
    }

    /**
     * The altitude signature: oxygen down and staying down. It exists to stop the card reading
     * adaptation as poor recovery — at altitude a raised heart rate is the body working, not failing.
     */
    @Test
    fun `sustained low blood oxygen reads as altitude, one night does not`() {
        val settled = listOf(97.0, 96.5, 97.0, 96.0, 97.0, 93.0, 92.5, 93.0)
        val r = RecoveryRegime.detect(emptyMap(), 100, settled)
        assertTrue("three low nights is a place", (r.altitudeNights ?: 0) >= 2)
        assertTrue((r.spo2Drop ?: 0.0) >= RecoveryRegime.ALTITUDE_DROP_PCT)

        val blip = listOf(97.0, 96.5, 97.0, 96.0, 97.0, 96.5, 97.0, 92.0)
        assertNull("one low night is a bad contact", RecoveryRegime.detect(emptyMap(), 100, blip).altitudeNights)
    }
}
