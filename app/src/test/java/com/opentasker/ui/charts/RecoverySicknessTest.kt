package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second conjunction — a long night after a quiet evening.
 *
 * ## The episode this is built from
 *
 * 白い熊 felt ill on 2026-09-11 and 2026-09-12. The night between the 10th and the 11th was, in the
 * numbers actually recorded on the band:
 *
 * | quantity | that night | z against the seven before |
 * |---|---|---|
 * | time asleep | **636 min** | **+4.09** (baseline 442) |
 * | going-to-bed heart rate | **74.0 bpm** | **−0.67** (baseline 76.5) |
 * | nocturnal heart rate | 66.8 | +0.8 bpm on its own baseline |
 * | RMSSD | 27.3 | +6 % |
 * | SpO₂ | 97 | unchanged, if anything higher |
 *
 * **The figures above are the corrected ones.** The first pass read the night that BEGINS on
 * 2026-09-11 as the night before the bad day, when it is the night after it started, and built a
 * fixture pairing 636 minutes with a 70 bpm evening — two numbers from different nights. The real
 * pre-illness evening was an ordinary 74 bpm; the 64 bpm one (z = −2.70) came the following night.
 * A fixture built from a mix-up asserts fiction, so these are read straight out of the archive.
 *
 * Every counted marker sat inside its usual band. [RecoveryResult.illnessSigns] could not fire *by
 * construction* — Radin's rule wants sleep below baseline and this was 43 % above it. The fixtures
 * below are those numbers, so the test fails if the rule stops catching the thing it was written for.
 *
 * The tests are also written to fail if it catches too much: a long lie-in on its own is a Sunday,
 * and a quiet evening on its own is a rest day.
 */
class RecoverySicknessTest {

    /** 白い熊's own sleep minutes for the seven nights before 2026-09-11. */
    private val priorSleep = listOf(432.0, 474.0, 534.0, 192.0, 432.0, 582.0, 442.0)

    /**
     * The going-to-bed heart rates of those same nights.
     *
     * Medians of the first hour after sleep ONSET, which is what [RecoverySource.bedtimeHr]
     * computes — not of the 22:00 clock hour, which is what the first analysis used and which put an
     * observation on the wrong night.
     */
    private val priorBedtime = listOf(77.0, 76.5, 70.0, 74.0, 76.0, 79.0, 73.0)

    private val priorHr = listOf(62.2, 77.8, 64.2, 69.8, 72.0, 63.3, 66.0)
    private val priorFelt = listOf(1.0, 2.0, 3.0, 2.0, 2.0, 2.0, 3.0)
    private val priorTemp = listOf(36.4, 36.45, 36.42, 36.38, 36.44, 36.41, 36.43)

    private fun sleepBand(v: Double?) = Recovery.band(
        RecoveryMarker.SLEEP, v, priorSleep, Recovery.SLEEP_MEANINGFUL_MIN,
        RecoveryConfidence.ESTABLISHED, counted = true,
    )

    private fun bedtimeBand(v: Double?) = Recovery.band(
        RecoveryMarker.BEDTIME_HR, v, priorBedtime, Recovery.HR_MEANINGFUL_BPM,
        RecoveryConfidence.ESTABLISHED, counted = false,
        sigmaFloor = (Recovery.median(priorBedtime) ?: 0.0) * Recovery.HR_SIGMA_FLOOR_FRACTION,
    )

    private fun assemble(
        sleepMinutes: Double?,
        bedtimeHr: Double?,
        nocturnalHr: Double = 66.8,
        nights: Int = 20,
    ) = Recovery.assemble(
        nightStartMs = 0L,
        nocturnalHr = Recovery.bandNocturnalHr(nocturnalHr, priorHr, Recovery.confidenceFor(nights)),
        sleep = sleepBand(sleepMinutes),
        felt = Recovery.band(
            RecoveryMarker.FELT, 3.0, priorFelt, Recovery.FELT_MEANINGFUL_STEPS,
            RecoveryConfidence.ESTABLISHED, counted = true,
        ),
        temperature = Recovery.band(
            RecoveryMarker.TEMPERATURE, null, priorTemp, Recovery.TEMP_MEANINGFUL_C,
            RecoveryConfidence.ESTABLISHED, counted = false, oneSidedHigh = true,
        ),
        temperatureSustained = false,
        lateEffortMinutesBeforeSleep = null,
        nightsOfHistory = nights,
        bedtimeHr = bedtimeBand(bedtimeHr),
    )

    /** The night itself: 10.6 h asleep, to bed at 70 bpm. This is the case the flag exists for. */
    @Test
    fun `the 2026-09-10 night fires`() {
        val r = assemble(sleepMinutes = 636.0, bedtimeHr = 74.0)
        assertTrue("the pair that actually preceded the illness must fire", r.sicknessBehaviour)
    }

    /**
     * And it fires on a heart-rate limb that only just clears its threshold.
     *
     * Worth asserting on its own, because it is the honest shape of the evidence: the sleep limb
     * cleared ±0.5 SD eight times over and the heart-rate limb by about a third of one. A change
     * that tightened the conjunction even slightly would lose this episode entirely, and would do it
     * silently.
     */
    @Test
    fun `the heart-rate limb only just clears the threshold`() {
        val bed = bedtimeBand(74.0)
        val z = bed.z ?: error("no z on an established baseline")
        assertTrue("z = $z", z <= -Recovery.CONJUNCTION_SIGMA)
        assertTrue("but only just: z = $z", z > -1.0)
    }

    /**
     * The night AFTER it started does not fire, and that is correct rather than a miss.
     *
     * 2026-09-11: a dramatic evening drop to 64 bpm, and sleep that ran SHORT. Neither conjunction
     * covers that pattern — Radin's wants the heart rate up, this one wants sleep up — and inventing
     * a third to cover a night 白い熊 already knew about would be fitting a rule to one observation.
     */
    @Test
    fun `the night during the illness does not fire`() {
        val r = assemble(sleepMinutes = 383.0, bedtimeHr = 64.0)
        assertFalse(r.sicknessBehaviour)
        assertFalse(r.illnessSigns)
    }

    /**
     * And the counting rule still says nothing about it — which is the whole reason for a second
     * flag rather than a wider first one.
     */
    @Test
    fun `that same night trips no counted marker and no published conjunction`() {
        val r = assemble(sleepMinutes = 636.0, bedtimeHr = 74.0)
        assertEquals("no counted marker fired", 0, r.adverseCount)
        assertFalse("Radin's rule cannot fire on sleep ABOVE baseline", r.illnessSigns)
    }

    @Test
    fun `an ordinary night does not fire`() {
        assertFalse(assemble(sleepMinutes = 440.0, bedtimeHr = 78.0).sicknessBehaviour)
    }

    /** A long lie-in on its own is a Sunday. Both limbs are required. */
    @Test
    fun `a long night alone does not fire`() {
        val r = assemble(sleepMinutes = 660.0, bedtimeHr = 79.0)
        assertFalse(r.sicknessBehaviour)
    }

    /** A quiet evening on its own is a rest day. */
    @Test
    fun `a low bedtime heart rate alone does not fire`() {
        assertFalse(assemble(sleepMinutes = 430.0, bedtimeHr = 68.0).sicknessBehaviour)
    }

    /**
     * A short night with a low evening must not fire either.
     *
     * The limb is sleep ABOVE baseline, not sleep unusual. Reading it as "sleep moved" would make
     * this rule fire on exactly the nights Radin's already covers and turn two flags into one noisy
     * one.
     */
    @Test
    fun `a short night with a quiet evening does not fire`() {
        assertFalse(assemble(sleepMinutes = 250.0, bedtimeHr = 68.0).sicknessBehaviour)
    }

    /**
     * Nothing fires without enough history to know what normal is.
     *
     * The same gate [RecoveryResult.illnessSigns] carries, for the same reason: a conjunction of two
     * z-scores computed on a dispersion estimate that is itself ±29 % is a false-alarm generator.
     */
    @Test
    fun `it stays silent while the baseline is provisional`() {
        val r = assemble(sleepMinutes = 636.0, bedtimeHr = 74.0, nights = 9)
        assertEquals(RecoveryConfidence.PROVISIONAL, r.confidence)
        assertFalse(r.sicknessBehaviour)
    }

    /** A missing bedtime heart rate is not a low one. */
    @Test
    fun `an absent limb does not fire`() {
        assertFalse(assemble(sleepMinutes = 636.0, bedtimeHr = null).sicknessBehaviour)
        assertFalse(assemble(sleepMinutes = null, bedtimeHr = 74.0).sicknessBehaviour)
    }

    /**
     * The two flags read different patterns and must not both fire on one night.
     *
     * Radin's wants heart rate up and sleep down; this one wants sleep up and the evening down. They
     * are mutually exclusive in the sleep limb by construction, and a change that made them overlap
     * would mean one of them had stopped meaning what its name says.
     */
    @Test
    fun `the two conjunctions cannot both fire`() {
        for (sleep in listOf(200.0, 300.0, 440.0, 560.0, 636.0, 700.0)) {
            for (bed in listOf(64.0, 70.0, 78.0, 86.0)) {
                for (hr in listOf(62.0, 66.8, 74.0, 80.0)) {
                    val r = assemble(sleepMinutes = sleep, bedtimeHr = bed, nocturnalHr = hr)
                    assertFalse(
                        "both fired at sleep=$sleep bed=$bed hr=$hr",
                        r.illnessSigns && r.sicknessBehaviour,
                    )
                }
            }
        }
    }

    /**
     * The bedtime marker is display-only and must stay that way.
     *
     * A fourth counted marker would silently turn the ≥2-of-3 rule — 92 % PPV, 100 % NPV in Nuuttila
     * 2024 — into a ≥2-of-4 rule with no evidence behind it at all.
     */
    @Test
    fun `the bedtime marker never reaches the count`() {
        val r = assemble(sleepMinutes = 636.0, bedtimeHr = 95.0)
        assertFalse(r.adverseMarkers.contains(RecoveryMarker.BEDTIME_HR))
        assertTrue(r.markers.any { it.marker == RecoveryMarker.BEDTIME_HR && !it.counted })
    }
}
