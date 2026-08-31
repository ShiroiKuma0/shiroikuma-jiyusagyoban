package com.opentasker.ui.charts

/**
 * One capsule per clock hour: the min and max actually measured inside it.
 *
 * This is what Hume's `H` tab draws, and checking our decode against it is what proved the decode
 * right — their 2026-08-04 headline of 58–91 bpm against our pooled 58–91, with all seven of their
 * capsules matching ours to the bpm.
 *
 * **An envelope is not a summary of a summary.** [lo] and [hi] are real samples that occurred; there
 * is no mean anywhere in this file. A capsule says "between these two readings, this hour" — which is
 * exactly true — where a bar of hourly averages would draw a number nobody ever measured. That is
 * 白い熊's brief applied to a different mark.
 *
 * ### Pool heart rate before enveloping it
 *
 * The `hr` stream carries two populations — a periodic series and an extra reading taken at each SpO₂
 * measurement, running +7.46 bpm higher. The *line* must split them or it draws a sawtooth. The
 * *envelope* must NOT: Hume's day range matches the pooled population, and dropping the
 * SpO₂-coincident readings would clip the top off every capsule. Split for the line, pool for the
 * envelope. See `docs/hume-band-protocol.md` §2.
 *
 * Pure Kotlin: hours come from a caller-supplied bucketing so this file needs no zone and no Android.
 */

/** One hour's extent. [n] is how many readings it is built from — 1 means lo == hi. */
data class HourBucket(
    /** Epoch millis of the hour's start. */
    val startMs: Long,
    val lo: Double,
    val hi: Double,
    val n: Int,
) {
    val span: Double get() = hi - lo
}

/** Systolic over diastolic for one hour, as one dumbbell. Either end may be absent. */
data class DumbbellBucket(
    val startMs: Long,
    val upper: HourBucket?,
    val lower: HourBucket?,
)

object HourlyEnvelope {

    const val HOUR_MS: Long = 3_600_000L

    /**
     * Bucket [points] into clock hours.
     *
     * Anchored to absolute time — `floor(t / HOUR_MS)` — never to array index, for the same reason
     * LTTB's buckets are: index buckets re-partition when the window shifts by one sample, so the
     * capsules would visibly crawl while panning.
     *
     * Empty hours are omitted rather than emitted as zero-width capsules: an hour with no reading is
     * a gap, and a mark drawn at a value nobody measured is exactly what this pipeline refuses to do.
     */
    fun bucket(points: List<ChartPoint>, offsetMs: Long = 0L): List<HourBucket> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<HourBucket>()
        var startMs = Long.MIN_VALUE
        var lo = 0.0
        var hi = 0.0
        var n = 0
        for (p in points.sortedBy { it.tMs }) {
            val bucketStart = Math.floorDiv(p.tMs - offsetMs, HOUR_MS) * HOUR_MS + offsetMs
            if (bucketStart != startMs) {
                if (n > 0) out += HourBucket(startMs, lo, hi, n)
                startMs = bucketStart
                lo = p.value
                hi = p.value
                n = 0
            }
            if (p.value < lo) lo = p.value
            if (p.value > hi) hi = p.value
            n++
        }
        if (n > 0) out += HourBucket(startMs, lo, hi, n)
        return out
    }

    /**
     * Pair two series into dumbbells, hour by hour.
     *
     * Both ends share one axis because they share one unit (mmHg). A second y-scale for the second
     * series would be the classic dual-axis mistake: it lets any two series be made to look
     * correlated by choosing the scales.
     */
    fun dumbbells(
        upper: List<ChartPoint>,
        lower: List<ChartPoint>,
        offsetMs: Long = 0L,
    ): List<DumbbellBucket> {
        val u = bucket(upper, offsetMs).associateBy { it.startMs }
        val l = bucket(lower, offsetMs).associateBy { it.startMs }
        return (u.keys + l.keys).sorted().map { DumbbellBucket(it, u[it], l[it]) }
    }

    /** The extent across every bucket, for the axis. Null when there is nothing to draw. */
    fun extent(buckets: List<HourBucket>): ClosedFloatingPointRange<Double>? {
        if (buckets.isEmpty()) return null
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (b in buckets) {
            if (b.lo < lo) lo = b.lo
            if (b.hi > hi) hi = b.hi
        }
        return lo..hi
    }
}
