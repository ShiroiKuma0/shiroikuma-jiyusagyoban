package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The no-overshoot invariant IS 白い熊's requirement, expressed as a test:
 *
 * > The graphs we show should then be smooth — but these must be as close to the actual
 * > measurements as possible.
 *
 * A spline that overshoots draws an SpO₂ of 101 %, or a heart-rate trough below every sample around
 * it. Those are values that were never measured, so the curve must be incapable of producing them.
 */
class PchipTest {

    private fun points(vararg pairs: Pair<Long, Double>): List<ChartPoint> =
        pairs.map { ChartPoint(it.first, it.second) }

    @Test
    fun `the drawn curve never leaves the bracketing samples`() {
        // A deliberately vicious series: a lone spike, a lone trough, a flat run, a cliff.
        val series = points(
            0L to 60.0,
            120_000L to 61.0,
            240_000L to 140.0,
            360_000L to 62.0,
            480_000L to 62.0,
            600_000L to 62.0,
            720_000L to 41.0,
            840_000L to 95.0,
            960_000L to 96.0,
        )
        val spans = Pchip.beziers(series)
        assertEquals(series.size - 1, spans.size)

        spans.forEachIndexed { i, span ->
            val lo = min(span.y0, span.y1)
            val hi = max(span.y0, span.y1)
            val (curveLo, curveHi) = Pchip.extremaOn(span, samples = 200)
            assertTrue(
                "span $i undershoots: curve reaches $curveLo below the bracketing $lo",
                curveLo >= lo - 1e-9,
            )
            assertTrue(
                "span $i overshoots: curve reaches $curveHi above the bracketing $hi",
                curveHi <= hi + 1e-9,
            )
        }
    }

    @Test
    fun `min and max of the drawn curve equal min and max of the samples`() {
        val series = points(
            0L to 97.0, 600_000L to 98.0, 1_200_000L to 100.0, 1_800_000L to 96.0, 2_400_000L to 91.0,
        )
        val spans = Pchip.beziers(series)
        var curveLo = Double.MAX_VALUE
        var curveHi = -Double.MAX_VALUE
        for (span in spans) {
            val (lo, hi) = Pchip.extremaOn(span, samples = 400)
            curveLo = min(curveLo, lo)
            curveHi = max(curveHi, hi)
        }
        assertEquals(91.0, curveLo, 1e-9)
        // The point of the SpO2 case: 100 % is the ceiling, and the curve must not draw 101.
        assertEquals(100.0, curveHi, 1e-9)
    }

    @Test
    fun `slope is forced to zero at every local extremum`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(10.0, 20.0, 10.0, 10.0)
        val m = Pchip.slopes(xs, ys)
        assertEquals("the peak at index 1 must be flat", 0.0, m[1], 1e-12)
        assertEquals("the trough at index 2 must be flat", 0.0, m[2], 1e-12)
    }

    @Test
    fun `a monotone run stays monotone`() {
        val series = points(0L to 50.0, 100L to 55.0, 200L to 70.0, 300L to 71.0, 400L to 90.0)
        for (span in Pchip.beziers(series)) {
            var previous = Pchip.evaluate(span, 0.0)
            for (i in 1..100) {
                val v = Pchip.evaluate(span, i / 100.0)
                assertTrue("the curve dipped inside a rising run", v >= previous - 1e-9)
                previous = v
            }
        }
    }

    @Test
    fun `two points make one straight span, and one point makes none`() {
        assertEquals(1, Pchip.beziers(points(0L to 1.0, 10L to 2.0)).size)
        assertTrue(Pchip.beziers(points(0L to 1.0)).isEmpty())
        assertTrue(Pchip.beziers(emptyList()).isEmpty())
    }

    @Test
    fun `a flat series stays exactly flat`() {
        val series = points(0L to 58.0, 120_000L to 58.0, 240_000L to 58.0, 360_000L to 58.0)
        for (span in Pchip.beziers(series)) {
            for (i in 0..50) {
                assertEquals(58.0, Pchip.evaluate(span, i / 50.0), 1e-12)
            }
        }
    }
}
