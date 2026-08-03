package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HampelTest {

    @Test
    fun `an injected spike is flagged`() {
        val values = List(20) { 60.0 } .toMutableList()
        values[10] = 140.0
        val flags = Hampel.flag(values, halfWindow = 3, sigmas = 3.5, minScale = 2.0)
        assertTrue("the spike must be flagged", flags[10])
        assertEquals("nothing else may be flagged", 1, flags.count { it })
    }

    /**
     * The single most important test in this file.
     *
     * In a quiet window the MAD is exactly 0. Without the floor the threshold is 0, so EVERY
     * deviation — including a real one-bpm change — clears it, and the filter goes berserk in
     * precisely the calmest, most trustworthy stretches of the night.
     */
    @Test
    fun `a constant run with one honest wobble is left alone by the minScale floor`() {
        val values = List(15) { 58.0 }.toMutableList()
        values[7] = 59.0
        val flags = Hampel.flag(values, halfWindow = 3, sigmas = 3.5, minScale = 2.0)
        assertFalse("a 1 bpm change in a quiet window is not an outlier", flags[7])
        assertEquals(0, flags.count { it })
    }

    @Test
    fun `without a floor the same wobble would be flagged — so the floor is required`() {
        // minScale must be > 0; the constructor enforces it rather than trusting the caller.
        val values = List(15) { 58.0 }.toMutableList()
        values[7] = 59.0
        val tiny = Hampel.flag(values, halfWindow = 3, sigmas = 3.5, minScale = 1e-9)
        assertTrue("this is the failure mode the floor exists to prevent", tiny[7])

        var threw = false
        try {
            Hampel.flag(values, halfWindow = 3, sigmas = 3.5, minScale = 0.0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("minScale of zero must be refused outright", threw)
    }

    @Test
    fun `flagging never alters a value`() {
        val values = listOf(60.0, 61.0, 200.0, 62.0, 63.0, 61.0, 60.0)
        val copy = values.toList()
        Hampel.flag(values, halfWindow = 3, sigmas = 3.0, minScale = 2.0)
        assertEquals("the input must be untouched — Hampel flags, it does not replace", copy, values)
    }

    @Test
    fun `a short series and a disabled window flag nothing`() {
        assertEquals(listOf(false, false), Hampel.flag(listOf(1.0, 900.0), 3, 3.0, 1.0))
        assertEquals(5, Hampel.flag(List(5) { it.toDouble() }, halfWindow = 0, sigmas = 3.0, minScale = 1.0).size)
        assertFalse(Hampel.flag(List(5) { it.toDouble() }, 0, 3.0, 1.0).any { it })
    }
}

class ChartQualifyTest {

    private val hr = MetricSpecs.HEART_RATE

    private fun series(vararg values: Double, cadenceSec: Int = 120): List<ChartPoint> =
        values.mapIndexed { i, v -> ChartPoint(i * cadenceSec * 1000L, v) }

    @Test
    fun `zeros are no-reading, not measurements of zero`() {
        val out = ChartQualify.qualify(series(60.0, 0.0, 61.0, 0.0, 62.0), hr)
        assertEquals(3, out.points.size)
        assertEquals(2, out.noReading)
        assertEquals(0, out.outOfRange)
    }

    @Test
    fun `values outside the physiological range never reach the statistics`() {
        val out = ChartQualify.qualify(series(60.0, 300.0, 61.0, 10.0, 62.0), hr)
        assertEquals(listOf(60.0, 61.0, 62.0), out.points.map { it.value })
        assertEquals(2, out.outOfRange)
    }

    @Test
    fun `the slew gate flags a dropout spike but does not delete it`() {
        // 60 -> 130 in one 120 s step is 70 bpm, past the 40 bpm limit.
        val out = ChartQualify.qualify(series(60.0, 130.0, 61.0, 60.0), hr)
        assertEquals("the sample stays — it WAS read, and must be provable", 4, out.points.size)
        assertTrue("the impossible jump is flagged", out.rejected[1])
        assertEquals(1, out.rejectedCount)
        assertEquals("a flagged artefact is not a no-reading", 0, out.noReading)
        assertEquals(0, out.outOfRange)
        assertEquals("and it is off the curve", 3, out.retained().size)
    }

    @Test
    fun `a slew artefact is withheld from the Hampel statistics`() {
        // Two spikes far enough apart that the first, left in the window, would inflate the MAD
        // enough to let the second through.
        val values = MutableList(24) { 60.0 }
        values[6] = 150.0
        values[16] = 150.0
        val out = ChartQualify.qualify(series(*values.toDoubleArray()), hr)
        assertTrue("first spike flagged", out.rejected[6])
        assertTrue("second spike flagged too", out.rejected[16])
    }

    @Test
    fun `the slew gate does not condemn the first sample after a real gap`() {
        val points = listOf(
            ChartPoint(0L, 60.0),
            ChartPoint(120_000L, 62.0),
            // Two hours later, legitimately different.
            ChartPoint(7_320_000L, 110.0),
            ChartPoint(7_440_000L, 112.0),
        )
        val out = ChartQualify.qualify(points, hr)
        assertEquals("a post-gap sample is data, not an artefact", 4, out.points.size)
        assertEquals(0, out.rejectedCount)
    }

    /**
     * SpO₂ and temperature carry NO slew gate, and that is deliberate.
     *
     * The hand-off specified ">3 % SpO₂" and ">0.5 °C" alongside ">40 bpm" as bounds on "a single
     * 120 s step" — but those two are sampled every 10 and 30 minutes. Over ten minutes a
     * four-point SpO₂ swing is ordinary physiology. On 白い熊's real data the gate condemned 53 of
     * 430 adjacent pairs in a series that never left 91–100.
     */
    @Test
    fun `SpO2 keeps a real ten-minute swing that a 120-second slew limit would have killed`() {
        val points = listOf(96.0, 100.0, 96.0, 99.0, 95.0, 100.0)
            .mapIndexed { i, v -> ChartPoint(i * 600_000L, v) }
        val out = ChartQualify.qualify(points, MetricSpecs.SPO2)
        assertEquals(6, out.points.size)
        assertEquals("none of these are artefacts", 0, out.rejectedCount)
        assertNull(MetricSpecs.SPO2.slewPerStep)
        assertNull(MetricSpecs.TEMPERATURE.slewPerStep)
        assertEquals(40.0, MetricSpecs.HEART_RATE.slewPerStep)
    }

    @Test
    fun `step counts keep their zeros`() {
        val steps = MetricSpec(
            key = "steps_min", label = "歩数", unit = "", cadenceSec = 60,
            validMin = 0.0, validMax = 250.0, zeroIsNoReading = false, slewPerStep = null,
            hampelHalfWindow = 0, hampelSigmas = 0.0, hampelMinScale = 1.0,
            yMin = 0.0, yMax = 200.0, decimals = 0,
        )
        val out = ChartQualify.qualify(series(0.0, 12.0, 0.0, 0.0, 30.0, cadenceSec = 60), steps)
        assertEquals("0 is a real step count", 5, out.points.size)
        assertEquals(0, out.noReading)
        assertFalse("steps must never be Hampel-filtered", out.rejected.any { it })
    }

    /**
     * The interleaved heart-rate split, against the rule measured on 白い熊's own data: an `hr`
     * sample belongs to the SpO₂-coincident population exactly when an SpO₂ sample shares its
     * timestamp. The seconds-field heuristic the hand-off proposed would misclassify a quarter of
     * them.
     */
    @Test
    fun `interleaved heart rate splits on the SpO2 timestamp, not on the seconds field`() {
        val heartRate = listOf(
            ChartPoint(1_000_030_000L, 65.0),
            ChartPoint(1_000_090_014L, 73.0),
            ChartPoint(1_000_150_030L, 64.0),
            // Seconds :22 — the hand-off's :14/:34 heuristic would call this periodic. It is not.
            ChartPoint(1_000_210_022L, 74.0),
        )
        val spo2 = setOf(1_000_090_014L, 1_000_210_022L)
        val (periodic, coincident) = ChartQualify.splitHeartRate(heartRate, spo2)
        assertEquals(listOf(65.0, 64.0), periodic.map { it.value })
        assertEquals(listOf(73.0, 74.0), coincident.map { it.value })
    }
}
