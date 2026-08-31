package com.opentasker.ui.charts

/**
 * The chart pipeline's data types.
 *
 * Pure Kotlin, no Android and no Compose, so the whole filter/decimate/smooth chain is JVM-testable.
 * Only the renderer touches a Canvas.
 *
 * 白い熊's brief governs every type here:
 *
 * > I don't think it's good to average the data: only to remove outlier peaks and troughs, and it
 * > should probably be smoothed out between the points if there are extreme outlier readings. The
 * > graphs we show should then be smooth — but these must be as close to the actual measurements as
 * > possible.
 *
 * So: nothing in this file can hold a value that was not measured. There is no type for "a bucket
 * mean" because there is no bucket mean, and [QualifiedSeries] deliberately offers no way to write a
 * value — a filter may flag a sample, and that is all it may do.
 */

/** One measurement: wall-clock epoch millis, and the value in the metric's own unit. */
data class ChartPoint(val tMs: Long, val value: Double)

/**
 * Samples that survived the range and slew gates, plus which of them the Hampel filter distrusts.
 *
 * [points] and [rejected] are the same length and index-aligned. There is no setter for a value and
 * no constructor that takes replacements: the textbook Hampel filter substitutes the window median
 * for an outlier, which would draw a number that never occurred. Encoding that in the type is
 * cheaper than remembering not to do it.
 */
class QualifiedSeries(
    val points: List<ChartPoint>,
    val rejected: List<Boolean>,
    /** Sentinels and zeros meaning "no reading" — non-measurements, neither outliers nor gaps. */
    val noReading: Int,
    /** Values outside the metric's physiological range, or failing the slew gate. */
    val outOfRange: Int,
) {
    init {
        require(points.size == rejected.size) { "points and rejected must be index-aligned" }
    }

    val rejectedCount: Int get() = rejected.count { it }

    /** The samples the filter kept, in order. */
    fun retained(): List<ChartPoint> = points.filterIndexed { i, _ -> !rejected[i] }
}

/** A contiguous run of retained samples — no gap and no rejection run inside it. */
data class ChartSegment(val points: List<ChartPoint>)

/**
 * One cubic Bézier span in DATA space (x = epoch millis as a Double, y = the metric's unit).
 *
 * A Hermite cubic is exactly a cubic Bézier, so the PCHIP curve is emitted directly as control
 * points and drawn with one `cubicTo` per sample. No per-pixel evaluation anywhere.
 */
data class BezierSpan(
    val x0: Double, val y0: Double,
    val c1x: Double, val c1y: Double,
    val c2x: Double, val c2y: Double,
    val x1: Double, val y1: Double,
)

/** A segment ready to draw: the real samples it kept, and the curve through them. */
data class RenderSegment(val points: List<ChartPoint>, val beziers: List<BezierSpan>)

/** What the footer reports. Not debug output — this is what makes the filtering trustworthy. */
data class ChartStats(
    val samples: Int,
    val rejected: Int,
    val gaps: Int,
    val noReading: Int,
) {
    /** e.g. `718 samples · 3 rejected · 2 gaps · 41 no-reading`. */
    fun summary(): String = buildString {
        append("$samples samples")
        if (rejected > 0) append(" · $rejected rejected")
        if (gaps > 0) append(" · $gaps gaps")
        if (noReading > 0) append(" · $noReading no-reading")
    }
}

/**
 * Everything the renderer needs, in data space. Built off the UI thread and cached; the draw lambda
 * only applies an affine transform to it.
 */
data class RenderModel(
    val segments: List<RenderSegment>,
    /** Stretches with no data, drawn as a tint. Never a dashed connector — that reads as data. */
    val gaps: List<LongRange>,
    /** Flagged samples at their REAL values, for the hollow ✕ marks. */
    val rejectedPoints: List<ChartPoint>,
    val stats: ChartStats,
    /** The value range actually present, so the axis can auto-expand past its clinical band. */
    val dataMin: Double,
    val dataMax: Double,
) {
    val isEmpty: Boolean get() = segments.isEmpty() && rejectedPoints.isEmpty()

    companion object {
        val EMPTY = RenderModel(emptyList(), emptyList(), emptyList(), ChartStats(0, 0, 0, 0), 0.0, 0.0)
    }
}
