package com.opentasker.core.huawei

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statistics derived from the per-beat series.
 *
 * The HRV metrics are checked against hand-computable inputs — their definitions are unambiguous and
 * a test that re-implements the formula proves nothing. The respiratory rate is checked against a
 * **synthetic tachogram carrying a known breathing frequency**, which is the only honest way to test
 * a spectral estimator: a recorded fixture would have no ground truth to compare against, since the
 * whole point is that nothing else on this band measures breathing.
 */
class HuaweiBeatMetricsTest {

    /**
     * A minute of beats whose interval is sinusoidally modulated at [breathsPerMinute].
     *
     * This is what respiratory sinus arrhythmia actually does to the tachogram: the interval
     * lengthens on expiration and shortens on inspiration. [amplitudeMs] is the peak deviation.
     */
    private fun tachogram(
        breathsPerMinute: Double,
        seconds: Double = 60.0,
        meanMs: Int = 1000,
        amplitudeMs: Double = 40.0,
    ): List<Int> {
        val hz = breathsPerMinute / 60.0
        val out = ArrayList<Int>()
        var t = 0.0
        while (t < seconds) {
            val interval = meanMs + amplitudeMs * sin(2.0 * PI * hz * t)
            out += interval.toInt()
            t += interval / 1000.0
        }
        return out
    }

    // ---- the time-domain metrics --------------------------------------------------------------

    @Test
    fun `sdnn is the sample standard deviation`() {
        // Alternating 900/1100 around a mean of 1000: every deviation is exactly 100.
        val v = List(20) { if (it % 2 == 0) 900 else 1100 }
        val sdnn = HuaweiBeatMetrics.sdnn(v)!!
        // Sample SD with n − 1: 100 * sqrt(20/19).
        assertEquals(100.0 * kotlin.math.sqrt(20.0 / 19.0), sdnn, 1e-9)
    }

    @Test
    fun `rmssd is the root mean square of successive differences`() {
        val v = List(20) { if (it % 2 == 0) 900 else 1100 }
        // Every successive difference is 200.
        assertEquals(200.0, HuaweiBeatMetrics.rmssd(v)!!, 1e-9)
    }

    @Test
    fun `pnn50 counts differences strictly greater than 50 ms`() {
        // Differences of exactly 50 must NOT count — the metric is pNN50, not pNN50-or-equal, and
        // a 20 ms storage grid makes exact 50s reachable.
        assertEquals(0.0, HuaweiBeatMetrics.pnn50(List(20) { if (it % 2 == 0) 1000 else 1050 })!!, 1e-9)
        assertEquals(100.0, HuaweiBeatMetrics.pnn50(List(20) { if (it % 2 == 0) 1000 else 1060 })!!, 1e-9)
    }

    @Test
    fun `a short window returns nothing rather than a fragile number`() {
        val few = List(HuaweiBeatMetrics.MIN_BEATS - 1) { 1000 }
        assertNull(HuaweiBeatMetrics.sdnn(few))
        assertNull(HuaweiBeatMetrics.rmssd(few))
        assertNull(HuaweiBeatMetrics.pnn50(few))
        assertNull(HuaweiBeatMetrics.respirationBpm(few))
    }

    // ---- quality gating -----------------------------------------------------------------------

    @Test
    fun `usable drops the beats the band disowned and the ones we could not read`() {
        val beats = listOf(
            HuaweiBeats.Beat(1000, 100),
            HuaweiBeats.Beat(1010, 50),
            HuaweiBeats.Beat(9999, 0),      // the band says this one is rubbish
            HuaweiBeats.Beat(8888, null),   // the page stamp took its opinion
        )
        assertEquals(listOf(1000, 1010), HuaweiBeatMetrics.usable(beats))
    }

    // ---- artefact correction ------------------------------------------------------------------

    /**
     * A missed beat records ONE interval of twice the local value, and that is what wrecks RMSSD.
     *
     * The numbers are from 白い熊's own record of 2026-09-12 09:07: a steady 700-ish ms series with
     * 1290, 1362 and 2106 in it. Uncorrected that record's RMSSD is 439 ms.
     */
    @Test
    fun `a doubled interval is removed`() {
        val v = listOf(830, 1290, 838, 747, 688, 1362, 2106, 720, 676, 694, 705, 703, 717, 714)
        val out = HuaweiBeatMetrics.corrected(v)
        assertFalse("the doubled beats survive: $out", out.contains(2106) || out.contains(1362))
        assertTrue("and the ordinary ones do not", out.contains(717) && out.contains(714))
    }

    /** A spurious extra detection records a HALF interval, and goes the same way. */
    @Test
    fun `a halved interval is removed`() {
        val v = listOf(714, 709, 382, 713, 719, 717, 718, 734, 744, 711, 732, 730, 730, 723)
        assertFalse(HuaweiBeatMetrics.corrected(v).contains(382))
    }

    /**
     * The comparison is against the last ACCEPTED interval, not the last one seen.
     *
     * With a run of bad beats, comparing to the previous raw value lets the first artefact become
     * the reference and admits the second — so a run walks the filter away from the real rate
     * instead of being rejected by it.
     */
    @Test
    fun `a run of artefacts does not drag the reference with it`() {
        val v = listOf(700, 1400, 1450, 1500, 705, 710, 700, 695, 700, 705, 710, 700, 698, 702)
        val out = HuaweiBeatMetrics.corrected(v)
        assertTrue("every artefact dropped: $out", out.none { it > 900 })
        assertEquals("and nothing real lost", v.count { it < 900 }, out.size)
    }

    /** A clean series passes through untouched — the filter must not cost real variability. */
    @Test
    fun `a clean series is unchanged`() {
        // 白い熊's record of 2026-09-11 05:50, which needed no correction at all.
        val v = listOf(842, 883, 926, 900, 852, 901, 941, 921, 865, 916, 910, 886, 860, 846, 844)
        assertEquals(v, HuaweiBeatMetrics.corrected(v))
    }

    /**
     * The headline regression: an artefact-laden window must not report a plausible-looking RMSSD.
     *
     * 133 ms was the uncorrected median across three days of 白い熊's data — four to six times any
     * resting figure the band itself publishes, and it would have been believed. The bound here is
     * generous (60 ms) because the point is not the exact value but that a number of that kind can
     * never come out again.
     */
    @Test
    fun `rmssd over an artefact-laden window is not a heart-rate variability`() {
        val v = listOf(
            787, 624, 1434, 1950, 1398, 2350, 1158, 783, 772, 1402, 1021, 546, 933, 802, 770,
            792, 786, 798, 1152, 1229, 1272, 1268, 738, 1290, 1171, 732, 1086, 1466, 486, 1837,
        )
        val raw = HuaweiBeatMetrics.rmssd(v)!!
        assertTrue("the raw figure really is absurd ($raw ms)", raw > 300)
        val fixed = HuaweiBeatMetrics.rmssd(HuaweiBeatMetrics.corrected(v))
        assertTrue("corrected RMSSD is $fixed ms", fixed == null || fixed < 60.0)
    }

    /**
     * [HuaweiBeatMetrics.usable] corrects; [HuaweiBeatMetrics.timeSeries] does not.
     *
     * The split is load-bearing: the respiratory estimate measures a frequency, so an interval
     * deleted from under it deletes the time it occupied and slides every later beat earlier.
     */
    @Test
    fun `usable corrects and timeSeries does not`() {
        val beats = listOf(700, 1400, 705, 710, 700, 695, 700, 705, 710, 700, 698, 702, 699, 701)
            .map { HuaweiBeats.Beat(it, 100) }
        assertTrue("timeSeries keeps the artefact", HuaweiBeatMetrics.timeSeries(beats).contains(1400))
        assertFalse("usable removes it", HuaweiBeatMetrics.usable(beats).contains(1400))
    }

    /**
     * Every metric the sync can emit is named in the list a recompute retracts by.
     *
     * The failure this guards is silent and was met for real: a metric missing from that list is one
     * a repair cannot remove, so a stale value outlives the estimator that produced it — and the
     * rows that go stale are the damaged windows, whose stale values are the worst numbers in the
     * series. Asserted against the keys rather than against a count, so adding one and forgetting
     * the list fails here rather than on 白い熊's phone.
     */
    @Test
    fun `every derived beat metric can be retracted`() {
        val emitted = setOf(
            HuaweiRriKeys.BEAT_SDNN, HuaweiRriKeys.BEAT_RMSSD, HuaweiRriKeys.BEAT_PNN50,
            HuaweiRriKeys.BEAT_COUNT, HuaweiRriKeys.BEAT_RESP_BPM, HuaweiRriKeys.BEAT_RSA_MS,
        )
        assertEquals(emitted, HuaweiRriKeys.BEAT_METRICS.toSet())
        assertEquals(
            "the list has a duplicate",
            HuaweiRriKeys.BEAT_METRICS.size, HuaweiRriKeys.BEAT_METRICS.toSet().size,
        )
        assertTrue(
            "every one is a beat_ key",
            HuaweiRriKeys.BEAT_METRICS.all { it.startsWith("beat_") },
        )
    }

    // ---- the respiratory rate -----------------------------------------------------------------

    /**
     * The headline property: a tachogram breathing at a known rate reports that rate.
     *
     * Tolerance is one breath per minute because that is the resolution a 60-second window has —
     * 1/60 Hz is 0.0167 Hz and a breath per minute is 0.0167 Hz. Asking for better would be asking
     * the estimator to invent precision the measurement does not carry.
     */
    @Test
    fun `a known breathing rate is recovered`() {
        for (rate in listOf(10.0, 12.0, 15.0, 18.0, 21.0)) {
            val got = HuaweiBeatMetrics.respirationBpm(tachogram(rate))
            assertNotNull("no peak found at $rate breaths/min", got)
            assertTrue(
                "expected ~$rate breaths/min, got $got",
                abs(got!! - rate) <= 1.0,
            )
        }
    }

    /**
     * The estimate must be against TIME, not against beat number.
     *
     * This is the mistake that makes an RSA estimate quietly wrong rather than noisy: at 60 bpm the
     * two coincide exactly, so a spectrum taken over beat index passes every test built at a mean
     * interval of 1000 ms. At 100 bpm it is off by two thirds. Same breathing rate, different heart
     * rate — only a time-based estimate survives both.
     */
    @Test
    fun `the rate does not move when the heart rate does`() {
        val slow = HuaweiBeatMetrics.respirationBpm(tachogram(15.0, meanMs = 1000))!!
        val fast = HuaweiBeatMetrics.respirationBpm(tachogram(15.0, meanMs = 600))!!
        assertTrue("slow heart: $slow", abs(slow - 15.0) <= 1.0)
        assertTrue("fast heart: $fast", abs(fast - 15.0) <= 1.0)
    }

    /**
     * A trend must not be read as breathing — and the PEAK RATIO alone cannot tell.
     *
     * This is the case that made the amplitude gate necessary rather than optional. A heart rate
     * drifting through a window leaves a residual whose leakage all lands in the bottom bin of the
     * HF band, so the band is *more* sharply peaked than real breathing is: measured at a ratio of
     * 10.4, against 9.9 for a genuine 40 ms RSA. A ratio test alone therefore reports a confident
     * 9.0 breaths/min for a tachogram containing no oscillation whatsoever.
     *
     * What separates them is size: the trend's residual amplitude is 0.001 ms.
     */
    @Test
    fun `a pure trend produces no reading`() {
        val drifting = (0 until 60).map { 900 + it * 4 }
        assertNull(HuaweiBeatMetrics.respirationBpm(drifting))
        assertNull(HuaweiBeatMetrics.hfPeak(drifting))
    }

    /**
     * Flat noise produces null, not the argmax of a flat band — and the AMPLITUDE alone cannot tell.
     *
     * The mirror of the trend case, and why both gates exist. White noise at SD 20 ms carries a
     * perfectly respectable apparent amplitude of 6.6–11 ms, well over the floor; what it does not
     * carry is a peak — its ratio sits at a median of 3.1 against the 9.9 of real RSA.
     *
     * A rate rather than a count of failures, because the quantity being asserted is a false-positive
     * rate and twenty draws cannot measure one. The bound is 10 % against a threshold chosen at a
     * measured 5 %, which leaves room for the estimator to drift without leaving room for it to
     * break — [HuaweiBeatMetrics.PEAK_RATIO] carries the whole curve.
     */
    @Test
    fun `noise without a peak produces no reading`() {
        val rng = java.util.Random(20260912)
        var found = 0
        val trials = 200
        repeat(trials) {
            val noise = (0 until 60).map { 1000 + (rng.nextGaussian() * 20).toInt() }
            if (HuaweiBeatMetrics.respirationBpm(noise) != null) found++
        }
        assertTrue(
            "white noise produced $found/$trials confident readings",
            found <= trials / 10,
        )
    }

    /**
     * The amplitude travels with the rate, and it tracks the real modulation rather than the noise.
     *
     * This pair is the whole point of computing anything here: Huawei's f8 rises both when breathing
     * slows and deepens and when vagal tone climbs, and a rate plus an amplitude is what tells those
     * apart. An amplitude that moved with the noise floor instead of the signal would look like the
     * same measurement and be useless for it.
     */
    @Test
    fun `the RSA amplitude tracks the modulation, not the noise`() {
        for (amp in listOf(40.0, 25.0, 15.0)) {
            val got = HuaweiBeatMetrics.rsaAmplitudeMs(tachogram(15.0, amplitudeMs = amp))
            assertNotNull("no peak at an amplitude of $amp ms", got)
            // Windowing costs a fifth of the amplitude; what matters is that it is proportional and
            // not a floor everything piles onto.
            assertTrue("amplitude $amp ms reported as $got", got!! in (amp * 0.6)..(amp * 1.2))
        }
    }

    /** A constant tachogram has no spectrum at all and must not crash or answer. */
    @Test
    fun `a perfectly regular heart reports no breathing`() {
        assertNull(HuaweiBeatMetrics.respirationBpm(List(60) { 1000 }))
    }

    /** Rates outside the standard HF band are not reported as if they were inside it. */
    @Test
    fun `breathing below the band is not dragged into it`() {
        // 6 breaths/min = 0.1 Hz, below HF_LOW_HZ. The estimator must not report 9.
        val got = HuaweiBeatMetrics.respirationBpm(tachogram(6.0, seconds = 120.0))
        if (got != null) {
            assertTrue(
                "a 0.1 Hz oscillation was reported as $got breaths/min",
                got >= HuaweiBeatMetrics.HF_LOW_HZ * 60.0,
            )
            assertTrue("and it should not masquerade as the true rate", abs(got - 6.0) > 1.0)
        }
    }
}
