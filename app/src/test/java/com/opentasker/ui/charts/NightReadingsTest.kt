package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five readings the night table added on 2026-09-03, taken over the sleep window.
 *
 * Each one is a different way of getting the window wrong, which is why they are pinned separately:
 * the low reaches for a minimum where the level reaches for a mean, the medians must not swallow
 * readings from outside the night, and the staging must tell "no deep sleep" apart from "never
 * staged". None of them is banded — there is a test below for that too, because a marker asserts
 * something about 白い熊 that a bare measurement does not.
 */
class NightReadingsTest {

    private val start = 1_800_000_000_000L
    private val end = start + 8 * 3_600_000L

    /** Half-hour runs across the night: deep, REM, light, repeating, with one awake stretch. */
    private fun session(): SleepSession {
        val runs = (0 until 16).map { i ->
            SleepRun(
                start + i * 30 * 60_000L,
                start + (i + 1) * 30 * 60_000L,
                when {
                    i == 9 -> '5'          // awake
                    i % 3 == 0 -> '1'      // deep
                    i % 3 == 1 -> '3'      // REM
                    else -> '2'            // light
                },
            )
        }
        return SleepSession(start, end, runs)
    }

    private fun points(vararg pairs: Pair<Long, Double>) = pairs.map { ChartPoint(it.first, it.second) }

    @Test
    fun `the lowest heart rate is the minimum recorded inside the night`() {
        val hr = points(
            start - 60_000L to 41.0,              // before bed — not this night's floor
            start + 30 * 60_000L to 58.0,
            start + 4 * 3_600_000L to 49.0,       // the real low
            start + 6 * 3_600_000L to 55.0,
            end + 60_000L to 44.0,                // after waking
        )
        val m = RecoverySource.metricsFor(session(), hr, emptyList())
        assertEquals(49.0, m.lowestHr!!, 0.001)
    }

    /**
     * The low is not the level.
     *
     * [RecoverySource.nocturnalHr] averages a fixed four-hour window after onset and skips awake
     * runs; the low is the floor of the whole session. Two questions, two numbers — and a test, so
     * nobody later "simplifies" one into the other.
     */
    @Test
    fun `the low and the nocturnal level are different quantities`() {
        // Per minute, as the band actually records: the nocturnal window needs 25 samples inside
        // four hours before it will answer at all, which half-hourly points cannot supply.
        val hr = (0 until 8 * 60).map {
            ChartPoint(start + it * 60_000L, if (it == 200) 47.0 else 60.0)
        }
        val m = RecoverySource.metricsFor(session(), hr, emptyList())
        assertEquals("the floor is the one low minute", 47.0, m.lowestHr!!, 0.001)
        assertEquals("the level is the window's mean, barely moved by it", 60.0, m.nocturnalHr!!, 0.2)
    }

    @Test
    fun `blood oxygen and RMSSD are medians of what fell inside the night`() {
        val spo2 = points(
            start - 3_600_000L to 90.0,           // daytime spot check — must not count
            start + 3_600_000L to 96.0,
            start + 4 * 3_600_000L to 97.0,
            start + 5 * 3_600_000L to 95.0,
        )
        val hrv = points(
            start + 3_600_000L to 30.0,
            start + 4 * 3_600_000L to 38.0,
            start + 5 * 3_600_000L to 52.0,
            end + 3_600_000L to 12.0,             // after waking
        )
        val m = RecoverySource.metricsFor(session(), emptyList(), emptyList(), spo2, hrv)
        assertEquals(96.0, m.spo2!!, 0.001)
        assertEquals(38.0, m.hrvMs!!, 0.001)
    }

    @Test
    fun `nothing recorded in the window reads as absent, never as zero`() {
        val outside = points(start - 3_600_000L to 95.0)
        val m = RecoverySource.metricsFor(session(), emptyList(), emptyList(), outside, emptyList())
        assertNull(m.spo2)
        assertNull(m.hrvMs)
        assertNull(m.lowestHr)
    }

    /**
     * "No deep sleep" and "never staged" are different claims.
     *
     * A session with no runs at all has nothing to add up, and printing `0h00` for it would be the
     * table asserting a measurement the band never made.
     */
    @Test
    fun `an unstaged night reports no staging rather than zero`() {
        val staged = RecoverySource.metricsFor(session(), emptyList(), emptyList())
        assertEquals("five half-hour deep runs", 150.0, staged.deepMinutes!!, 0.001)

        val unstaged = RecoverySource.metricsFor(
            SleepSession(start, end, emptyList()), emptyList(), emptyList(),
        )
        assertNull(unstaged.deepMinutes)
        assertNull(unstaged.deepRemShare)
    }

    /** The readings reach the register's rows with their values intact. */
    @Test
    fun `the register carries the readings`() {
        val hr = (0 until 16).map { ChartPoint(start + it * 30 * 60_000L, 60.0 - it % 4) }
        val history = listOf(RecoverySource.metricsFor(session(), hr, emptyList()))
        val reading = SessionRegister.readNights(history) { 3.0 }.single()
        assertEquals(57.0, reading.lowestHr!!, 0.001)
        assertEquals(150.0, reading.deepMinutes!!, 0.001)
        assertNull("no blood oxygen was supplied", reading.spo2)
    }

    /**
     * Colouring the five did not smuggle them into the counting rule.
     *
     * 白い熊 asked for every column to carry a colour (2026-09-03), which meant banding deep, deep+REM
     * and RMSSD within-person — and a banded marker is one step away from being counted. It must
     * stay that step away: the headline counts THREE markers (≥2 of 3 → 92 % PPV in Nuuttila 2025),
     * and a rule over five is a different rule with no evidence behind it. This is the guard.
     */
    @Test
    fun `the coloured readings are never counted as adverse`() {
        // Twenty nights of steady deep sleep, then one with almost none — as adverse as this metric
        // can look, so if it were ever going to reach the count it would reach it here.
        val steady = (0 until 20).map {
            RecoverySource.NightMetrics(
                startMs = start + it * 86_400_000L,
                endMs = start + it * 86_400_000L + 8 * 3_600_000L,
                nocturnalHr = 60.0,
                sleepMinutes = 480.0,
                skinTemp = null,
                deepMinutes = 120.0,
                deepRemShare = 0.45,
                hrvMs = 40.0,
            )
        }
        val collapsed = steady.last().copy(
            startMs = start + 20 * 86_400_000L,
            endMs = start + 20 * 86_400_000L + 8 * 3_600_000L,
            deepMinutes = 10.0,
            deepRemShare = 0.08,
            hrvMs = 9.0,
        )
        val reading = SessionRegister.readNights(steady + collapsed) { 3.0 }.last()

        assertEquals("the worst step, so the colour did fire", 5, reading.deepStep)
        assertEquals(5, reading.deepRemStep)
        assertEquals(5, reading.hrvStep)
        // And the count is untouched: three markers, none of them these.
        assertEquals("still counted on the three markers alone", 0, reading.adverseCount)
    }

    /**
     * The published ladders, at their own boundaries.
     *
     * Pinned because they are cut points from the literature rather than choices of ours: a boundary
     * that drifts is a reference that has quietly become an opinion.
     */
    @Test
    fun `the published ladders sit on their documented cut points`() {
        assertEquals("96 % and up is the normal range", 1, RecoveryReference.spo2Step(96.0))
        assertEquals("95 % is the borderline", 2, RecoveryReference.spo2Step(95.0))
        assertEquals(3, RecoveryReference.spo2Step(93.0))
        assertEquals(4, RecoveryReference.spo2Step(90.0))
        assertEquals("below 90 % is hypoxaemia", 5, RecoveryReference.spo2Step(89.9))

        // The low rides Jensen's decades unchanged — deliberately the same ladder, not a shifted one.
        for (bpm in listOf(49.0, 59.0, 69.0, 79.0, 80.0)) {
            assertEquals(
                "the low and the level share one ladder",
                RecoveryReference.nocturnalHrStep(bpm),
                RecoveryReference.lowestHrStep(bpm),
            )
        }
    }
}
