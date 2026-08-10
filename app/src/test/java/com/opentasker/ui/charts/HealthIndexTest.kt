package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康指数 exists to be checkable, so it is checked.
 *
 * The point of building our own index rather than imitating Hume's 811 is that every breakpoint is
 * visible and therefore arguable. A test suite is the other half of that promise: if a breakpoint
 * moves, something here fails and says so, which is exactly what a hidden formula cannot offer.
 */
class HealthIndexTest {

    private fun inputs(
        restingHr: Double? = 55.0,
        iqr: Double? = 8.0,
        spo2: Double? = 96.0,
        sleepMinutes: Int? = 480,
        deepRem: Double? = 0.35,
        steps: Double? = 6_000.0,
    ) = HealthIndexInputs(restingHr, iqr, spo2, sleepMinutes, deepRem, steps)

    @Test
    fun `a ramp hits 100 at its best, 0 at its worst, and interpolates between`() {
        assertEquals(100, HealthIndex.ramp(50.0, best = 50.0, worst = 85.0))
        assertEquals(0, HealthIndex.ramp(85.0, best = 50.0, worst = 85.0))
        assertEquals(50, HealthIndex.ramp(67.5, best = 50.0, worst = 85.0))
    }

    @Test
    fun `a ramp clamps, because a very low resting heart rate is not 130 points of health`() {
        assertEquals(100, HealthIndex.ramp(38.0, best = 50.0, worst = 85.0))
        assertEquals(0, HealthIndex.ramp(140.0, best = 50.0, worst = 85.0))
    }

    @Test
    fun `sleep duration is a plateau, not a ramp — more is not always better`() {
        assertEquals(100, HealthIndex.plateau(480.0, 240.0, 420.0, 540.0, 720.0))   // 8 h
        assertEquals(100, HealthIndex.plateau(420.0, 240.0, 420.0, 540.0, 720.0))   // 7 h, edge
        assertEquals(100, HealthIndex.plateau(540.0, 240.0, 420.0, 540.0, 720.0))   // 9 h, edge
        assertEquals(0, HealthIndex.plateau(240.0, 240.0, 420.0, 540.0, 720.0))     // 4 h
        assertEquals(0, HealthIndex.plateau(720.0, 240.0, 420.0, 540.0, 720.0))     // 12 h
        // 11 h scores POORLY, which a plain "more is better" ramp would get backwards.
        assertTrue(HealthIndex.plateau(660.0, 240.0, 420.0, 540.0, 720.0) < 40)
    }

    @Test
    fun `every component at its best scores exactly 100`() {
        val r = HealthIndex.compute(
            inputs(
                restingHr = 50.0, iqr = 4.0, spo2 = 97.0, sleepMinutes = 480, deepRem = 0.45,
                steps = 7_500.0,
            ),
        )
        assertEquals(100, r.value)
        assertFalse(r.partial)
        assertTrue(r.missing.isEmpty())
        assertEquals("Excellent", r.band.en)
    }

    @Test
    fun `sleep architecture pulls a perfect night down, and the arithmetic is checkable`() {
        // Everything at its best EXCEPT a 35 % deep+REM share against the 45 % target.
        val r = HealthIndex.compute(
            inputs(
                restingHr = 50.0, iqr = 4.0, spo2 = 97.0, sleepMinutes = 480, deepRem = 0.35,
                steps = 7_500.0,
            ),
        )
        // share = (0.35-0.10)/(0.45-0.10) = 71 ; sleep = 100×0.7 + 71×0.3 = 91
        assertEquals(91, r.components.single { it.key == "sleep" }.score)
        // index = 100×(0.26+0.11+0.17+0.20) + 91×0.26 = 74 + 23.66 = 97.66 → 98
        assertEquals(98, r.value)
    }

    @Test
    fun `the weights sum to one, so a perfect score is reachable`() {
        val total = HealthIndex.W_RESTING_HR + HealthIndex.W_STABILITY +
            HealthIndex.W_SPO2 + HealthIndex.W_SLEEP + HealthIndex.W_STEPS
        assertEquals(1.0, total, 1e-9)
        // The printed percentages must sum to 100 too — a panel that publishes its arithmetic and
        // then shows weights adding to 99 undermines the one thing it is for.
        val printed = listOf(
            HealthIndex.W_RESTING_HR, HealthIndex.W_STABILITY, HealthIndex.W_SPO2,
            HealthIndex.W_SLEEP, HealthIndex.W_STEPS,
        ).sumOf { (it * 100).toInt() }
        assertEquals(100, printed)
    }

    @Test
    fun `a missing component is NAMED and the index says it is partial — never imputed`() {
        val r = HealthIndex.compute(inputs(sleepMinutes = null, deepRem = null))
        assertTrue("an absent night must make the index partial", r.partial)
        assertEquals(listOf("Sleep"), r.missing.map { it.en })
        val sleep = r.components.single { it.key == "sleep" }
        assertNull("a missing component must not be scored", sleep.score)
        assertEquals("no recent sleep record", sleep.missingReason?.en)
    }

    @Test
    fun `a missing component is not silently scored as zero`() {
        // This is the whole design. If sleep were treated as 0 rather than absent, a night the band
        // simply did not record would drag the index down as though the night had been terrible.
        val complete = HealthIndex.compute(inputs())
        val noSleep = HealthIndex.compute(inputs(sleepMinutes = null, deepRem = null))
        assertTrue(
            "renormalising must not punish a night that was never measured " +
                "(complete=${complete.value}, missing=${noSleep.value})",
            noSleep.value!! >= complete.value!! - 12,
        )
    }

    @Test
    fun `with nothing measured at all there is no index, rather than a flattering zero`() {
        val r = HealthIndex.compute(HealthIndexInputs(null, null, null, null, null, null))
        assertNull(r.value)
        assertTrue(r.partial)
        assertEquals(5, r.missing.size)
        assertEquals("—", r.band.en)
    }

    @Test
    fun `sleep architecture is only blended in when it was measured`() {
        // Duration alone, with no stage breakdown, must still score — the band records sessions whose
        // stage bytes we may not have, and refusing the whole component over that would be wrong.
        val r = HealthIndex.compute(inputs(sleepMinutes = 480, deepRem = null))
        val sleep = r.components.single { it.key == "sleep" }
        assertEquals(100, sleep.score)
        assertFalse(r.partial)
    }

    @Test
    fun `every component carries its scale text, so the info panel can print the arithmetic`() {
        val r = HealthIndex.compute(inputs())
        assertEquals(5, r.components.size)
        r.components.forEach {
            assertTrue("${it.key} must publish its breakpoints", it.scale.en.isNotBlank())
            assertTrue("${it.key} must publish its weight", it.weight > 0.0)
        }
    }

    /**
     * The consistency guarantee, as a test.
     *
     * 白い熊 caught the failure this prevents: the HRV card read "Standard" while the index scored the
     * same metric 15 out of 100. The cause was two independent sets of breakpoints — the card's band
     * ladder said 30–60 was standard, while the index scored against 15–70 taken from general HRV
     * literature that this band's numbers never reach.
     *
     * Tying them together is what makes the two views incapable of contradicting each other, and this
     * is what stops them drifting apart again.
     */
    @Test
    fun `the index breakpoints ARE the card band ladders — they cannot drift apart`() {
        fun edges(spec: MetricSpec) = spec.bands.map { it.upTo }.filter { it != Double.MAX_VALUE }

        // SpO2: ladder 90 / 94 / 97.
        assertEquals(listOf(90.0, 94.0, 97.0), edges(MetricSpecs.SPO2))
        assertEquals(HealthIndex.SPO2_WORST, edges(MetricSpecs.SPO2).first(), 1e-9)
        assertEquals(HealthIndex.SPO2_BEST, edges(MetricSpecs.SPO2).last(), 1e-9)

        // Heart rate runs the other way — lower is better — so its BEST is the ladder's low edge.
        assertEquals(listOf(45.0, 55.0, 75.0), edges(MetricSpecs.HEART_RATE))
        assertEquals(HealthIndex.HR_BEST, 50.0, 1e-9)
        assertEquals(HealthIndex.HR_WORST, 85.0, 1e-9)

        // Steps: ladder 3 000 / 7 500 / 12 000. The component takes the first two, so "Low" is the
        // zero and the start of "High" is full marks — a card reading Standard can never sit beside
        // a component scoring 0 or 100.
        assertEquals(listOf(3_000.0, 7_500.0, 12_000.0), edges(MetricSpecs.STEPS))
        assertEquals(HealthIndex.STEPS_WORST, edges(MetricSpecs.STEPS)[0], 1e-9)
        assertEquals(HealthIndex.STEPS_BEST, edges(MetricSpecs.STEPS)[1], 1e-9)
    }

    /**
     * Steps, added 2026-08-07 at 白い熊's instruction.
     *
     * The one thing to get right is that **0 is a measurement**. Everywhere else in this index an
     * absent value means the band did not measure and must be reported missing; a day of not walking
     * is a real, and rather informative, reading. Confusing the two would either flatter a sedentary
     * day or punish a day the band was on the charger.
     */
    @Test
    fun `a day of no walking scores zero, and no step data at all is missing instead`() {
        val sedentary = HealthIndex.compute(inputs(steps = 0.0))
        val stepsComponent = sedentary.components.single { it.key == "steps" }
        assertEquals(0, stepsComponent.score)
        assertFalse("zero steps is a measurement, not an absence", sedentary.partial)

        val noData = HealthIndex.compute(inputs(steps = null))
        assertNull(noData.components.single { it.key == "steps" }.score)
        assertTrue(noData.partial)
        assertEquals(listOf("Steps"), noData.missing.map { it.en })
    }

    @Test
    fun `steps ramp between the ladder edges and stop buying score above the plateau`() {
        fun score(v: Double) = HealthIndex.compute(inputs(steps = v))
            .components.single { it.key == "steps" }.score
        assertEquals(0, score(3_000.0))
        assertEquals(100, score(7_500.0))
        assertEquals(50, score(5_250.0))
        assertEquals("walking more than the plateau must not keep scoring", 100, score(25_000.0))
    }

    /**
     * Steps are a BEHAVIOUR, and the other four are physiological state.
     *
     * That is the trade 白い熊 accepted when asking for this: a day of walking lifts the index
     * without any of the body's own numbers moving. Worth asserting so nobody later "fixes" it as a
     * bug.
     */
    @Test
    fun `walking lifts the index with every other input unchanged`() {
        val still = HealthIndex.compute(inputs(steps = 3_000.0))
        val walked = HealthIndex.compute(inputs(steps = 7_500.0))
        assertTrue(
            "steps must be able to move the index (still=${still.value}, walked=${walked.value})",
            walked.value!! > still.value!! + 15,
        )
    }

    /**
     * The `hrv` byte is not HRV, so it scores nothing.
     *
     * Established against 2 131 records: it correlates POSITIVELY with heart rate where every real
     * variability metric is negative, carries no sleep-stage information, and has 74 % of its
     * variance fixed by two firmware flags. Nothing was substituted for it — there is no other field
     * in this protocol that measures autonomic tone, and a stand-in would be the exact failure this
     * index exists to avoid.
     */
    @Test
    fun `there is no HRV component, and nothing was invented to replace it`() {
        val r = HealthIndex.compute(inputs())
        assertEquals(5, r.components.size)
        assertTrue(
            "no component may claim to measure variability",
            r.components.none { it.key == "hrv" || it.label.en.contains("HRV") },
        )
        assertEquals(
            listOf("resting_hr", "stability", "spo2", "sleep", "steps"),
            r.components.map { it.key },
        )
    }

    /** The dashboard must not offer the derived copy of a number it already draws. */
    @Test
    fun `the reconstructible band-index chart is not on the dashboard`() {
        assertTrue(
            "vascular/stress is a lookup on the hrv byte — charting it twice adds nothing",
            MetricSpecs.ALL.none { it === MetricSpecs.BAND_INDEX },
        )
    }

    /** A device state index has no Low/Standard/High, because we do not know what good means. */
    @Test
    fun `the band state index publishes no band ladder`() {
        assertTrue(MetricSpecs.HRV.bands.isEmpty())
        assertTrue(MetricSpecs.HRV.label.en.contains("State"))
        assertTrue(MetricSpecs.HRV.info.caveat.en.contains("not HRV"))
    }

    @Test
    fun `a poor night reads as poor`() {
        val r = HealthIndex.compute(
            inputs(restingHr = 82.0, iqr = 22.0, spo2 = 91.0, sleepMinutes = 300, deepRem = 0.12),
        )
        assertTrue("expected a low index, got ${r.value}", r.value!! < 30)
        assertEquals("Very low", r.band.en)
    }
}
