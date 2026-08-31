package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.ceil

/**
 * S4 DECIMATE — Largest-Triangle-Three-Buckets.
 *
 * LTTB *selects actual samples*. It never computes a mean, so every point that survives it is a
 * measurement the band really took, which is the whole reason it is here rather than `AVG()` and a
 * `GROUP BY`. It preserves the visual envelope — spikes and troughs stay — where bucket means
 * flatten exactly the events worth seeing.
 *
 * At 白い熊's primary viewing scale it does nothing at all: 720 samples across a 1080 px plot needs
 * no decimation, so the chart simply *is* the measurements. It earns its keep at the zoomed-out end,
 * where a month is ~200k points.
 */
object Lttb {

    /**
     * The epoch that bucket boundaries are measured from.
     *
     * Buckets are anchored to ABSOLUTE TIME, not to array index. Index-partitioned LTTB
     * re-partitions every bucket when the window shifts by a single sample, so the selected set
     * visibly crawls while panning — points pop in and out under the finger. Anchoring costs a dozen
     * lines and makes the selection stable: pan the viewport and the same samples stay chosen.
     */
    private const val EPOCH_ANCHOR = 0L

    /**
     * Reduce [points] to about [target] samples, or return it untouched when it is already small
     * enough. [spanMs] is the viewport width, which sets the bucket size.
     *
     * Always keeps the first and last sample, as LTTB requires — they anchor the triangles.
     */
    fun decimate(points: List<ChartPoint>, target: Int, spanMs: Long): List<ChartPoint> {
        if (target < 3 || points.size <= target || points.size < 3) return points

        val bucketSpan = (spanMs.toDouble() / (target - 2)).coerceAtLeast(1.0)
        val buckets = partition(points, bucketSpan)
        if (buckets.size <= 2) return points

        val out = ArrayList<ChartPoint>(buckets.size)
        out += points.first()

        var previous = points.first()
        // The first and last buckets hold the anchors, so only the interior is selected from.
        for (b in 1 until buckets.size - 1) {
            val bucket = buckets[b]
            if (bucket.isEmpty()) continue

            // The next bucket's average is the third triangle vertex. This is the ONE place an
            // average appears in the whole pipeline, and it is never drawn: it only decides which
            // real sample to keep.
            val next = buckets.getOrNull(b + 1)?.takeIf { it.isNotEmpty() } ?: buckets.last()
            var nx = 0.0
            var ny = 0.0
            for (p in next) {
                nx += p.tMs.toDouble()
                ny += p.value
            }
            nx /= next.size
            ny /= next.size

            var best = bucket.first()
            var bestArea = -1.0
            for (p in bucket) {
                val area = abs(
                    (previous.tMs - nx) * (p.value - previous.value) -
                        (previous.tMs - p.tMs.toDouble()) * (ny - previous.value),
                )
                if (area > bestArea) {
                    bestArea = area
                    best = p
                }
            }
            out += best
            previous = best
        }

        out += points.last()
        return out
    }

    /** Points grouped by absolute-time bucket, in order, with empty buckets preserved as gaps. */
    private fun partition(points: List<ChartPoint>, bucketSpan: Double): List<List<ChartPoint>> {
        val out = mutableListOf<MutableList<ChartPoint>>()
        var currentIndex = Long.MIN_VALUE
        for (p in points) {
            val index = Math.floorDiv(p.tMs - EPOCH_ANCHOR, bucketSpan.toLong().coerceAtLeast(1L))
            if (index != currentIndex) {
                out += mutableListOf<ChartPoint>()
                currentIndex = index
            }
            out.last() += p
        }
        return out
    }

    /** `ceil(plotWidth / 2)` points, clamped — about one sample per two pixels. */
    fun targetFor(plotWidthPx: Float): Int =
        ceil(plotWidthPx / 2f).toInt().coerceIn(64, 2048)
}
