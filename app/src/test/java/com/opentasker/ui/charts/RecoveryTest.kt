package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回復 — the banding rules, and the reasons they are conjunctions.
 *
 * The tests below encode published operating points, not preferences. Where a number appears it
 * traces to a paper named in [Recovery]'s KDoc, and the tests are written so that loosening a
 * threshold breaks one.
 */
class RecoveryTest {

    private fun hrBand(
        value: Double?,
        history: List<Double>,
        confidence: RecoveryConfidence = RecoveryConfidence.ESTABLISHED,
    ) = Recovery.bandNocturnalHr(value, history, confidence)

    /** 白い熊's own eight nights, so the fixture is the real distribution rather than a tidy one. */
    private val realNights = listOf(62.26, 62.12, 62.54, 62.39, 66.32, 61.40, 60.39, 66.54)

    @Test
    fun `a quiet night sits in usual`() {
        val r = hrBand(62.0, realNights)
        assertEquals(RecoveryBand.USUAL, r.band)
        assertFalse(r.adverse)
    }

    @Test
    fun `a clearly elevated night fires high`() {
        val r = hrBand(70.0, realNights)
        assertEquals(RecoveryBand.HIGH, r.band)
        assertTrue("an elevation is the adverse direction", r.adverse)
    }

    /**
     * The first half of the conjunction: unusual is not enough.
     *
     * 白い熊's nights are quiet enough that the robust dispersion sits near the sensor's own noise
     * floor. Without the absolute floor, a 3 bpm move would clear 1.5 SD and fire — and 3 bpm is
     * below the 5–7 bpm the wearable-validation literature calls clinically meaningful.
     */
    @Test
    fun `a statistically unusual but trivial move does NOT fire`() {
        val baseline = Recovery.median(realNights)!!
        // +4 bpm on 白い熊's own nights is 1.8 robust SD — beyond the band edge — but under the
        // 5 bpm the validation literature calls clinically meaningful. It must not fire.
        val r = hrBand(baseline + 4.0, realNights)
        assertEquals(RecoveryBand.USUAL, r.band)
        assertTrue("it really is beyond 1.5 robust SD", (r.z ?: 0.0) > Recovery.BAND_SIGMA)
        assertTrue("and under the meaningful floor", (r.delta ?: 0.0) < Recovery.HR_MEANINGFUL_BPM)
    }

    /**
     * The second half: meaningful is not enough either, once the spread is genuinely wide.
     *
     * Someone whose nights swing 10 bpm should not be told a 6 bpm night is an event.
     */
    @Test
    fun `a meaningful move inside a wide personal spread does NOT fire`() {
        val noisy = listOf(52.0, 58.0, 64.0, 70.0, 55.0, 67.0, 61.0, 73.0)
        val baseline = Recovery.median(noisy)!!
        val r = hrBand(baseline + 6.0, noisy)
        assertEquals(RecoveryBand.USUAL, r.band)
        assertTrue("and it did exceed the 5 bpm floor", (r.delta ?: 0.0) > Recovery.HR_MEANINGFUL_BPM)
    }

    /**
     * The floor that stops a quiet fortnight manufacturing alarms.
     *
     * Repeated under identical conditions, nocturnal HR still moves ~3.5 % — that is the sensor. With
     * a pathologically tight history the MAD collapses toward zero, and without the floor a 2 bpm
     * drift would score z = 4.
     */
    @Test
    fun `the dispersion floor holds when the history is implausibly tight`() {
        val flat = List(14) { 58.0 }
        val r = hrBand(60.0, flat)
        assertEquals("2 bpm is not an event", RecoveryBand.USUAL, r.band)
        val sigma = 58.0 * Recovery.HR_SIGMA_FLOOR_FRACTION
        assertEquals(2.0 / sigma, r.z!!, 0.01)
    }

    @Test
    fun `a low resting heart rate is never adverse`() {
        val r = hrBand(52.0, realNights)
        assertEquals(RecoveryBand.LOW, r.band)
        assertFalse("sleeping with a lower heart rate is a good night, not an event", r.adverse)
    }

    // --- the short-history ladder -----------------------------------------------------------------

    @Test
    fun `under five nights nothing is banded at all`() {
        val r = hrBand(70.0, listOf(62.0, 63.0), RecoveryConfidence.COLLECTING)
        assertEquals(RecoveryBand.UNKNOWN, r.band)
        assertNull(r.z)
    }

    /**
     * Between 5 and 13 nights the dispersion estimate is itself ±29 %, so z-scores would produce
     * false alarms. Absolute thresholds sidestep it — and must still work.
     */
    @Test
    fun `provisional uses absolute thresholds and no z-score`() {
        val short = listOf(62.0, 61.0, 63.0, 62.5, 61.5, 62.2)
        val quiet = Recovery.band(
            RecoveryMarker.NOCTURNAL_HR, 64.0, short,
            Recovery.HR_MEANINGFUL_BPM, RecoveryConfidence.PROVISIONAL, counted = true,
        )
        val loud = Recovery.band(
            RecoveryMarker.NOCTURNAL_HR, 69.0, short,
            Recovery.HR_MEANINGFUL_BPM, RecoveryConfidence.PROVISIONAL, counted = true,
        )
        assertNull("no z-score may be shown on a dispersion we do not trust", quiet.z)
        assertEquals(RecoveryBand.USUAL, quiet.band)
        assertEquals("but a 7 bpm night is still worth saying", RecoveryBand.HIGH, loud.band)
    }

    @Test
    fun `the confidence ladder is where the literature puts it`() {
        assertEquals(RecoveryConfidence.COLLECTING, Recovery.confidenceFor(4))
        assertEquals(RecoveryConfidence.PROVISIONAL, Recovery.confidenceFor(5))
        assertEquals(RecoveryConfidence.PROVISIONAL, Recovery.confidenceFor(13))
        assertEquals(RecoveryConfidence.ESTABLISHED, Recovery.confidenceFor(14))
    }

    // --- temperature: one-sided, and never on one night -------------------------------------------

    private fun tempBand(value: Double, history: List<Double>) = Recovery.band(
        RecoveryMarker.TEMPERATURE, value, history,
        Recovery.TEMP_MEANINGFUL_C, RecoveryConfidence.ESTABLISHED, counted = false, oneSidedHigh = true,
    )

    /** 白い熊's measured nights: mean 36.44 °C, SD 0.11 — tight enough that 0.3 °C is a real event. */
    private val realTemps = listOf(36.50, 36.40, 36.40, 36.60, 36.50, 36.55, 36.40, 36.40, 36.20)

    /**
     * The floor is a property of heart rate, not a universal.
     *
     * 3.5 % of a 36.4 °C baseline is 1.27 °C — about four times the entire physiological signal, and
     * it would have silenced the temperature marker for ever. This test exists because that is
     * exactly what the first implementation did.
     */
    @Test
    fun `the heart-rate dispersion floor is not applied to other markers`() {
        val sigma = Recovery.madSigma(realTemps)!!
        assertTrue("the real dispersion is small", sigma < 0.2)
        assertTrue(
            "and 3.5 % of an absolute temperature would dwarf it",
            36.4 * Recovery.HR_SIGMA_FLOOR_FRACTION > sigma * 5,
        )
        assertEquals(RecoveryBand.HIGH, tempBand(37.0, realTemps).band)
    }

    @Test
    fun `a cold night is not a recovery signal`() {
        assertEquals(
            "wrist temperature is measuring the bedroom nearly as much as 白い熊",
            RecoveryBand.USUAL, tempBand(36.0, realTemps).band,
        )
    }

    @Test
    fun `a sustained warm night fires, a single one does not`() {
        val hot = tempBand(37.0, realTemps)
        assertEquals(RecoveryBand.HIGH, hot.band)

        val single = Recovery.assemble(
            nightStartMs = 0L,
            nocturnalHr = hrBand(62.0, realNights),
            sleep = sleepBand(430.0, realSleep),
            felt = feltBand(3.0, realFelt),
            temperature = hot,
            temperatureSustained = false,
            lateEffortMinutesBeforeSleep = null,
            nightsOfHistory = 20,
        )
        assertEquals(
            "one warm night is ambient, not illness",
            RecoveryBand.USUAL,
            single.markers.first { it.marker == RecoveryMarker.TEMPERATURE }.band,
        )
    }

    // --- the counting rule ------------------------------------------------------------------------

    private val realSleep = listOf(588.0, 382.0, 551.0, 639.0, 550.0, 611.0, 574.0, 498.0)
    private val realFelt = listOf(3.0, 3.0, 4.0, 3.0, 3.0, 4.0, 3.0, 3.0)

    private fun sleepBand(v: Double, h: List<Double>) = Recovery.band(
        RecoveryMarker.SLEEP, v, h, Recovery.SLEEP_MEANINGFUL_MIN, RecoveryConfidence.ESTABLISHED, true,
    )

    private fun feltBand(v: Double, h: List<Double>) = Recovery.band(
        RecoveryMarker.FELT, v, h, Recovery.FELT_MEANINGFUL_STEPS, RecoveryConfidence.ESTABLISHED, true,
    )

    private fun assemble(hr: Double, sleep: Double, felt: Double, nights: Int = 20) = Recovery.assemble(
        nightStartMs = 0L,
        nocturnalHr = hrBand(hr, realNights),
        sleep = sleepBand(sleep, realSleep),
        felt = feltBand(felt, realFelt),
        temperature = tempBand(36.45, realTemps),
        temperatureSustained = false,
        lateEffortMinutesBeforeSleep = null,
        nightsOfHistory = nights,
    )

    @Test
    fun `an ordinary night counts nothing`() {
        val r = assemble(hr = 62.0, sleep = 560.0, felt = 3.0)
        assertEquals(0, r.adverseCount)
        assertTrue(r.adverseMarkers.isEmpty())
    }

    @Test
    fun `one marker off is one, and it is named`() {
        val r = assemble(hr = 70.0, sleep = 560.0, felt = 3.0)
        assertEquals(1, r.adverseCount)
        assertEquals(listOf(RecoveryMarker.NOCTURNAL_HR), r.adverseMarkers)
    }

    /** The published operating point: two of three is where a composite starts beating its parts. */
    @Test
    fun `two markers off is the threshold the counting rule exists for`() {
        val r = assemble(hr = 70.0, sleep = 380.0, felt = 3.0)
        assertEquals(2, r.adverseCount)
        assertTrue(RecoveryMarker.NOCTURNAL_HR in r.adverseMarkers)
        assertTrue(RecoveryMarker.SLEEP in r.adverseMarkers)
    }

    @Test
    fun `temperature is shown but never counted`() {
        val r = Recovery.assemble(
            nightStartMs = 0L,
            nocturnalHr = hrBand(62.0, realNights),
            sleep = sleepBand(560.0, realSleep),
            felt = feltBand(3.0, realFelt),
            temperature = tempBand(37.2, realTemps),
            temperatureSustained = true,
            lateEffortMinutesBeforeSleep = null,
            nightsOfHistory = 20,
        )
        assertEquals(
            RecoveryBand.HIGH,
            r.markers.first { it.marker == RecoveryMarker.TEMPERATURE }.band,
        )
        assertEquals("it is an illness flag, not a recovery component", 0, r.adverseCount)
    }

    @Test
    fun `sleeping much longer than usual is not adverse`() {
        val r = assemble(hr = 62.0, sleep = 700.0, felt = 3.0)
        assertEquals(RecoveryBand.HIGH, r.markers.first { it.marker == RecoveryMarker.SLEEP }.band)
        assertEquals(0, r.adverseCount)
    }

    // --- the Radin illness conjunction ------------------------------------------------------------

    @Test
    fun `illness signs need BOTH halves of the published rule`() {
        // Radin's thresholds are ±0.5 SD, deliberately looser than our 1.5 SD display band.
        val both = assemble(hr = 65.0, sleep = 470.0, felt = 3.0)
        assertTrue("HR up AND sleep short is the published conjunction", both.illnessSigns)

        val hrOnly = assemble(hr = 65.0, sleep = 600.0, felt = 3.0)
        assertFalse("an elevated heart rate alone is not the rule", hrOnly.illnessSigns)
    }

    @Test
    fun `illness signs stay silent until the baseline is established`() {
        val r = assemble(hr = 65.0, sleep = 470.0, felt = 3.0, nights = 10)
        assertFalse("a 10-night baseline cannot support a 0.5 SD conjunction", r.illnessSigns)
    }
}
