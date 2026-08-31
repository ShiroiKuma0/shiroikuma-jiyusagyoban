package com.opentasker.ui.charts

/**
 * S3 SEGMENT — split retained samples into contiguous runs.
 *
 * Viewport-independent: this depends on the sampling cadence and on what the filter flagged, not on
 * how far 白い熊 has zoomed in. It runs once per (metric, day) and is cached; only the final
 * data→pixel map is per-frame.
 */
object ChartSegments {

    /**
     * Split at gaps and at rejection runs.
     *
     * | situation | behaviour |
     * |---|---|
     * | retained samples closer than the threshold | one segment — the curve runs through |
     * | farther apart than the threshold | break, and report the gap so it can be tinted |
     * | ONE sample rejected | interpolate across it — one bad sample is noise worth bridging |
     * | [runIsGap] or more consecutive rejected | break — the sensor was wrong, there is nothing to bridge |
     *
     * That last rule is what keeps this honest. Bridging a long run of rejections would draw a
     * smooth line across a stretch where the sensor was demonstrably not working.
     */
    fun split(
        series: QualifiedSeries,
        gapThresholdMs: Long,
        runIsGap: Int = 3,
    ): Pair<List<ChartSegment>, List<LongRange>> {
        val segments = mutableListOf<ChartSegment>()
        val gaps = mutableListOf<LongRange>()
        var current = mutableListOf<ChartPoint>()

        fun close() {
            if (current.size >= 1) segments += ChartSegment(current)
            current = mutableListOf()
        }

        var i = 0
        while (i < series.points.size) {
            if (series.rejected[i]) {
                // How long does this run of rejections go on for?
                var end = i
                while (end + 1 < series.points.size && series.rejected[end + 1]) end++
                val runLength = end - i + 1
                if (runLength >= runIsGap) {
                    val before = current.lastOrNull()?.tMs
                    close()
                    val after = series.points.getOrNull(end + 1)?.tMs
                    if (before != null && after != null) gaps += before..after
                }
                // A shorter run is simply skipped: the curve interpolates across it, and the
                // rejected samples are still reported at their real values for the ✕ marks.
                i = end + 1
                continue
            }

            val point = series.points[i]
            val previous = current.lastOrNull()
            if (previous != null && point.tMs - previous.tMs > gapThresholdMs) {
                gaps += previous.tMs..point.tMs
                close()
            }
            current += point
            i++
        }
        close()

        // A lone point cannot be drawn as a curve, but it is a real measurement and must still show
        // as a dot, so single-point segments are kept rather than dropped.
        return segments to gaps
    }
}
