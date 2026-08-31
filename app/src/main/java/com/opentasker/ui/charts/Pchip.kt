package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * S5 SMOOTH — Fritsch–Carlson monotone cubic interpolation (PCHIP).
 *
 * Natural cubic splines and Catmull-Rom both overshoot near a local extremum. Here an overshoot is
 * not a cosmetic blemish: it means the chart *draws* an SpO₂ of 101 %, or a heart-rate trough below
 * every sample around it. PCHIP guarantees no overshoot at extrema, which gives exactly the property
 * 白い熊 asked for:
 *
 * > **min/max of the drawn curve == min/max of the retained samples.**
 *
 * That is a testable invariant, and [PchipTest] tests it.
 *
 * The price is that a genuine single-sample peak renders as a slight plateau, since PCHIP flattens
 * at every local extremum. That is the right trade against overshoot, and it is mitigated by drawing
 * the real sample dots on top — which serves the brief directly, because those dots are the actual
 * measurements, visible.
 */
object Pchip {

    /**
     * Fritsch–Carlson slopes.
     *
     * At a local extremum — where the secants change sign, or either is flat — the slope is forced
     * to zero. That single rule is what forbids overshoot.
     */
    fun slopes(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        require(n == ys.size) { "xs and ys must be the same length" }
        if (n == 0) return DoubleArray(0)
        if (n == 1) return DoubleArray(1)

        val h = DoubleArray(n - 1)
        val d = DoubleArray(n - 1)
        for (k in 0 until n - 1) {
            h[k] = xs[k + 1] - xs[k]
            require(h[k] > 0.0) { "xs must be strictly increasing" }
            d[k] = (ys[k + 1] - ys[k]) / h[k]
        }

        val m = DoubleArray(n)
        if (n == 2) {
            m[0] = d[0]
            m[1] = d[0]
            return m
        }

        for (k in 1 until n - 1) {
            m[k] = if (d[k - 1] * d[k] <= 0.0) {
                // A sign change or a flat secant means k is a local extremum. Zero here is the
                // no-overshoot guarantee.
                0.0
            } else {
                val w1 = 2.0 * h[k] + h[k - 1]
                val w2 = h[k] + 2.0 * h[k - 1]
                (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
            }
        }
        m[0] = endpointSlope(d[0], d[1], h[0], h[1])
        m[n - 1] = endpointSlope(d[n - 2], d[n - 3], h[n - 2], h[n - 3])
        return m
    }

    /**
     * The clamped three-point endpoint formula.
     *
     * An unclamped endpoint slope is the other classic way a monotone spline overshoots — at the
     * very first and last sample, where there is nothing beyond to hold it down.
     */
    private fun endpointSlope(d0: Double, d1: Double, h0: Double, h1: Double): Double {
        val slope = ((2.0 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        return when {
            sign(slope) != sign(d0) -> 0.0
            sign(d0) != sign(d1) && abs(slope) > abs(3.0 * d0) -> 3.0 * d0
            else -> slope
        }
    }

    /**
     * The curve as cubic Bézier control points, in data space.
     *
     * A Hermite cubic IS a cubic Bézier, with controls at `(x_k + h/3, y_k + m_k·h/3)` and
     * `(x_{k+1} − h/3, y_{k+1} − m_{k+1}·h/3)`. So the whole curve is one Path with one `cubicTo`
     * per retained sample — exact, and far cheaper than evaluating the polynomial per pixel. This is
     * the single biggest accuracy-and-performance win in the design.
     */
    fun beziers(points: List<ChartPoint>): List<BezierSpan> {
        if (points.size < 2) return emptyList()
        val xs = DoubleArray(points.size) { points[it].tMs.toDouble() }
        val ys = DoubleArray(points.size) { points[it].value }
        val m = slopes(xs, ys)

        return (0 until points.size - 1).map { k ->
            val h = xs[k + 1] - xs[k]
            BezierSpan(
                x0 = xs[k], y0 = ys[k],
                c1x = xs[k] + h / 3.0, c1y = ys[k] + m[k] * h / 3.0,
                c2x = xs[k + 1] - h / 3.0, c2y = ys[k + 1] - m[k + 1] * h / 3.0,
                x1 = xs[k + 1], y1 = ys[k + 1],
            )
        }
    }

    /** Evaluate the Hermite cubic on `[k, k+1]` at parameter [t] in `0..1`. For tests. */
    fun evaluate(span: BezierSpan, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * span.y0 +
            3.0 * u * u * t * span.c1y +
            3.0 * u * t * t * span.c2y +
            t * t * t * span.y1
    }

    /** The tightest bound the curve can reach on a span — used by the no-overshoot test. */
    fun extremaOn(span: BezierSpan, samples: Int = 64): Pair<Double, Double> {
        var lo = min(span.y0, span.y1)
        var hi = maxOf(span.y0, span.y1)
        for (i in 0..samples) {
            val v = evaluate(span, i.toDouble() / samples)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return lo to hi
    }
}
