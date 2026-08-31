package com.opentasker.ui.charts.huawei

/**
 * How much of each metric the band actually recorded, and how far apart the readings were.
 *
 * Pure Kotlin, so the arithmetic that will be used to justify changing a gate value is itself
 * tested.
 *
 * ## Why this exists before the charts do
 *
 * Every tuned value in [HuaweiMetricSpecs] is a placeholder, because a second device arrives with no
 * measurements behind it and copying the Hume band's figures would dress a guess as a fact. This is
 * the instrument that ends that: the observed inter-sample gaps ARE the cadence, and once there are
 * enough of them the provisional rows can be rewritten with numbers that were seen rather than
 * assumed.
 *
 * It is also the direct answer to the question the whole parallel-running phase exists to settle —
 * *is the Huawei band catching what the Hume band catches* — which is a question about coverage, not
 * about values.
 *
 * ## What it deliberately does not do
 *
 * It does not infer a cadence from one gap, does not report a percentile it cannot compute, and does
 * not fill a missing figure with zero. A metric with fewer than two samples has no gaps, and
 * "unmeasured" is reported as absent rather than as 0 — the same distinction the sync status makes
 * between a floor that has been observed and one that has not.
 */
data class HuaweiCoverage(
    /** The chart key, `hw:`-prefixed. */
    val key: String,
    val samples: Int,
    val firstSeconds: Long?,
    val lastSeconds: Long?,
    /** Gap percentiles in seconds, or null when there are fewer than two samples. */
    val p50GapSec: Int?,
    val p90GapSec: Int?,
    val p99GapSec: Int?,
    val longestGapSec: Int?,
) {
    /** The span between the first and last reading. Not the same as how much was measured in it. */
    val spanSeconds: Long?
        get() = if (firstSeconds != null && lastSeconds != null) lastSeconds - firstSeconds else null

    /**
     * The band's real cadence for this metric, as observed — the figure that should replace the
     * placeholder in [HuaweiMetricSpecs]. The median rather than the mean, because a single long
     * gap where the band was off the wrist would drag a mean into meaninglessness.
     */
    val observedCadenceSec: Int? get() = p50GapSec

    /**
     * What fraction of the span actually carries readings at the observed cadence, 0–1.
     *
     * Null until there is a cadence to measure against. This is the number that answers "is it
     * catching everything": a metric sampling every 60 s across an hour should have ~60 readings,
     * and this says how close it came.
     */
    val density: Double?
        get() {
            val cadence = observedCadenceSec?.takeIf { it > 0 } ?: return null
            val span = spanSeconds?.takeIf { it > 0 } ?: return null
            val expected = span.toDouble() / cadence + 1.0
            return (samples / expected).coerceIn(0.0, 1.0)
        }

    companion object {
        /**
         * @param times epoch seconds, in any order — sorted here rather than trusted, because a
         *   caller that hands them over unsorted would otherwise produce negative gaps and a
         *   plausible-looking cadence computed from them.
         */
        fun from(key: String, times: List<Long>): HuaweiCoverage {
            if (times.isEmpty()) {
                return HuaweiCoverage(key, 0, null, null, null, null, null, null)
            }
            val sorted = times.sorted()
            if (sorted.size < 2) {
                return HuaweiCoverage(key, 1, sorted.first(), sorted.first(), null, null, null, null)
            }
            val gaps = sorted.zipWithNext { a, b -> (b - a).toInt() }.sorted()
            return HuaweiCoverage(
                key = key,
                samples = sorted.size,
                firstSeconds = sorted.first(),
                lastSeconds = sorted.last(),
                p50GapSec = percentile(gaps, 50),
                p90GapSec = percentile(gaps, 90),
                p99GapSec = percentile(gaps, 99),
                longestGapSec = gaps.last(),
            )
        }

        /** Nearest-rank on an already-sorted list. No interpolation: these are observed gaps, and a
         * value between two of them is a gap that never occurred. */
        private fun percentile(sortedGaps: List<Int>, pct: Int): Int? {
            if (sortedGaps.isEmpty()) return null
            val rank = Math.ceil(pct / 100.0 * sortedGaps.size).toInt().coerceIn(1, sortedGaps.size)
            return sortedGaps[rank - 1]
        }
    }
}
