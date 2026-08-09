package com.opentasker.ui.charts

/**
 * S1 → S5, in the one order that works.
 *
 * ```
 * Room rows (indexed on localTs)
 *  ├─ S1 QUALIFY   sentinel drop, plausible-range gate, slew gate, split interleaved HR
 *  ├─ S2 HAMPEL    rolling median + MAD; FLAG only, never replace
 *  ├─ S3 SEGMENT   split into contiguous runs at gaps
 *  │     ^^^ viewport-INDEPENDENT — computed once per (metric, day), cached
 *  ├─ S4 LTTB      per segment, only when n > threshold, buckets anchored to absolute time
 *  ├─ S5 PCHIP     Fritsch–Carlson slopes → cubic Bézier control points, in data space
 *  └─ S6 MAP       data→pixel affine + Path build            [main thread, per frame]
 * ```
 *
 * S1–S3 are cadence-bound, not viewport-bound: they depend on the data and the filter parameters,
 * never on how far 白い熊 has zoomed. Only S4 onward cares about the viewport, and only S6 runs per
 * frame.
 */
object ChartPipeline {

    /**
     * How many cadences of silence make a gap. Named because a second caller now derives its own
     * threshold — heart rate's curve re-segments the periodic series alone — and the two must not
     * drift apart by one of them hard-coding a different number.
     */
    const val DEFAULT_GAP_MULTIPLIER = 3

    /**
     * Everything up to segmentation. Cache this per (metric, day, filterParamsVersion) — it does not
     * change when the viewport moves.
     */
    fun qualifyAndSegment(
        raw: List<ChartPoint>,
        spec: MetricSpec,
        gapMultiplier: Int = DEFAULT_GAP_MULTIPLIER,
        mixedCadence: Boolean = false,
    ): QualifiedChunk {
        val qualified = ChartQualify.qualify(raw, spec)
        val threshold = gapThresholdMs(qualified.retained(), spec, gapMultiplier, mixedCadence)
        val (segments, gaps) = ChartSegments.split(qualified, threshold)
        return QualifiedChunk(
            segments = segments,
            gaps = gaps,
            rejectedPoints = qualified.points.filterIndexed { i, _ -> qualified.rejected[i] },
            noReading = qualified.noReading + qualified.outOfRange,
            retainedCount = qualified.points.size - qualified.rejectedCount,
        )
    }

    /**
     * How long a silence has to be before it counts as a gap.
     *
     * MEASURED, not assumed. The nominal cadences are what the band is documented to do; what it
     * actually does is another matter, and only the data knows. The periodic heart-rate series is
     * nominally 120 s, but its real median interval on 白い熊's device is **240 s** — the band skips
     * slots. Taking the nominal figure at face value put the threshold at 360 s and declared 231 of
     * 848 intervals to be gaps, which shredded the line into 235 fragments and tinted half the chart
     * red.
     *
     * So: the larger of the nominal cadence and the observed median interval, times the multiplier.
     * Taking the larger means a burst of closely-spaced samples can never make the threshold
     * *tighter* than the metric is documented to be, while a band that samples more slowly than
     * advertised is believed.
     */
    fun gapThresholdMs(
        points: List<ChartPoint>,
        spec: MetricSpec,
        multiplier: Int,
        mixedCadence: Boolean = false,
    ): Long {
        val nominal = spec.cadenceSec * 1000L
        if (points.size < 8) return nominal * multiplier
        val intervals = LongArray(points.size - 1) { points[it + 1].tMs - points[it].tMs }
        intervals.sort()
        // The median is the right statistic for ONE series and the wrong one for a mixture. Pooled
        // heart rate interleaves a 120 s and a 600 s cadence: its median lands at 176 s, so the
        // threshold falls at 528 s — just under the 600 s spacing that is perfectly ordinary wherever
        // only the SpO₂-coincident reading exists. Measured on real data that declared 67 spurious
        // ~10-minute gaps out of 70, tinting most of the chart red over nothing.
        //
        // A high percentile survives the mixture: it sits above the slower mode instead of between
        // the two. Only the pooled series needs it, so the caller says so rather than every metric
        // paying for it.
        val representative = if (mixedCadence) intervals[(intervals.size * 9) / 10] else intervals[intervals.size / 2]
        return maxOf(nominal, representative) * multiplier
    }

    /**
     * S4 + S5 for a given viewport. Cheap enough to run on every level-of-detail change, which is
     * debounced to 120 ms after the last gesture event.
     *
     * [curveMode] exists because a linear polyline is the maximally honest rendering and costs one
     * `when`.
     */
    fun render(
        chunk: QualifiedChunk,
        spanMs: Long,
        plotWidthPx: Float,
        curveMode: ChartCurveMode = ChartCurveMode.PCHIP,
    ): RenderModel {
        val target = Lttb.targetFor(plotWidthPx)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE

        val rendered = chunk.segments.mapNotNull { segment ->
            // Decimate PER SEGMENT, never across a gap — a triangle spanning a gap would pick points
            // to represent a stretch where nothing was measured.
            val kept = Lttb.decimate(segment.points, target, spanMs)
            if (kept.isEmpty()) return@mapNotNull null
            for (p in kept) {
                if (p.value < min) min = p.value
                if (p.value > max) max = p.value
            }
            RenderSegment(
                points = kept,
                beziers = when (curveMode) {
                    ChartCurveMode.PCHIP -> Pchip.beziers(kept)
                    ChartCurveMode.LINEAR, ChartCurveMode.STEP -> emptyList()
                },
            )
        }

        for (p in chunk.rejectedPoints) {
            if (p.value < min) min = p.value
            if (p.value > max) max = p.value
        }

        return RenderModel(
            segments = rendered,
            gaps = chunk.gaps,
            rejectedPoints = chunk.rejectedPoints,
            stats = ChartStats(
                samples = chunk.retainedCount,
                rejected = chunk.rejectedPoints.size,
                gaps = chunk.gaps.size,
                noReading = chunk.noReading,
            ),
            dataMin = if (min == Double.MAX_VALUE) 0.0 else min,
            dataMax = if (max == -Double.MAX_VALUE) 0.0 else max,
        )
    }
}

/** The viewport-independent half of the pipeline, ready to cache. */
data class QualifiedChunk(
    val segments: List<ChartSegment>,
    val gaps: List<LongRange>,
    val rejectedPoints: List<ChartPoint>,
    val noReading: Int,
    val retainedCount: Int,
) {
    companion object {
        val EMPTY = QualifiedChunk(emptyList(), emptyList(), emptyList(), 0, 0)
    }
}

enum class ChartCurveMode { PCHIP, LINEAR, STEP }
