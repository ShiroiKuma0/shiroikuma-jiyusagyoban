package com.opentasker.core.band

import kotlinx.serialization.Serializable

/**
 * The census — the point of the sync hand-off.
 *
 * The band's buffers are small: roughly 600 HRV records at a 120-second cadence is about 20 hours,
 * and older data is silently overwritten. Nothing on our side removes it — the Hume app never sends
 * the destructive mode either, and a factory reset is the only thing that empties the band — so what
 * is being measured here is the band's own ring buffer. Its true depth per stream is unknown and can
 * only be MEASURED, over days of real use with varied gaps.
 *
 * Pure Kotlin on purpose: the estimation is the part worth testing, and it must be testable without a
 * device.
 */

/** What one stream did during one sync. */
@Serializable
data class BandStreamStat(
    val frames: Int = 0,
    val pages: Int = 0,
    val records: Int = 0,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    /** The oldest record the band still holds for this stream — the buffer floor. */
    val oldestLocalTs: Long? = null,
    val newestLocalTs: Long? = null,
    /** Records the gap since the previous sync should have produced, at this stream's cadence. */
    val expectedRecords: Int = 0,
    /** max(0, expected − inserted). Non-zero means the previous gap outran the buffer. */
    val lostRecords: Int = 0,
    val elapsedMs: Long = 0,
    val end: String = "",
    val error: String? = null,
)

/** Sampling cadences measured on the device, in seconds. */
val BAND_CADENCE_SEC: Map<String, Int> = mapOf(
    "hr" to 120,
    "hrv" to 120,
    "spo2" to 600,
    "temp" to 1800,
    "detail" to 60,
)

/**
 * What we can say about one stream's buffer depth so far.
 *
 * [lowerBoundHours] is the largest gap that came back with NO loss — the buffer is at least that.
 * [upperBoundHours] is the smallest gap that DID lose records — the buffer is at most that. They
 * converge from opposite sides as gaps vary.
 */
data class BandCapacityEstimate(
    val stream: String,
    val lowerBoundHours: Double?,
    val upperBoundHours: Double?,
    val maxRecordsSeen: Int,
    val confidence: String,
)

/** One sync's worth of input to the estimator, in the order they happened. */
data class BandSyncSummary(
    val startedAt: Long,
    val gapHours: Double,
    val stats: Map<String, BandStreamStat>,
)

object BandCensus {

    /**
     * How many records a gap of [gapSeconds] should have produced for [streamKey].
     *
     * Zero for a stream with no known cadence — daily and sleep are event-shaped, not sampled, so
     * "expected records" is meaningless for them and inventing a number would manufacture fake loss.
     */
    fun expectedRecords(streamKey: String, gapSeconds: Long): Int {
        val cadence = BAND_CADENCE_SEC[streamKey] ?: return 0
        if (gapSeconds <= 0) return 0
        return (gapSeconds / cadence).toInt()
    }

    /** Records the band could not give us because the gap outran its buffer. */
    fun lostRecords(expected: Int, inserted: Int): Int = maxOf(0, expected - inserted)

    /**
     * Bound each stream's buffer depth from a series of syncs.
     *
     * A sync with loss says the buffer is SMALLER than that gap; a sync without loss says it is AT
     * LEAST that gap. A sync whose stream errored or timed out is ignored entirely — it says nothing
     * about capacity, and counting it as "no loss" would inflate the lower bound with a sync that
     * never actually read anything.
     */
    fun summarize(syncs: List<BandSyncSummary>): List<BandCapacityEstimate> {
        val keys = syncs.flatMap { it.stats.keys }.distinct().sorted()
        return keys.map { key ->
            var lower: Double? = null
            var upper: Double? = null
            var maxRecords = 0
            for (sync in syncs) {
                val stat = sync.stats[key] ?: continue
                if (stat.error != null) continue
                maxRecords = maxOf(maxRecords, stat.records)
                if (sync.gapHours <= 0.0) continue
                if (stat.lostRecords > 0) {
                    upper = minOf(upper ?: sync.gapHours, sync.gapHours)
                } else if (stat.expectedRecords > 0) {
                    // Only a gap we could actually have lost data over is evidence of depth.
                    lower = maxOf(lower ?: sync.gapHours, sync.gapHours)
                }
            }
            BandCapacityEstimate(
                stream = key,
                lowerBoundHours = lower,
                upperBoundHours = upper,
                maxRecordsSeen = maxRecords,
                confidence = confidenceOf(lower, upper),
            )
        }
    }

    /**
     * How much the two bounds are worth yet.
     *
     * "bounded" once they straddle the answer, "at least"/"at most" when only one side has been
     * observed, and "unknown" until some gap has been long enough to be informative. Deliberately
     * conservative: a single sync proves nothing about a ring buffer.
     */
    private fun confidenceOf(lower: Double?, upper: Double?): String = when {
        lower != null && upper != null && upper > lower -> "bounded"
        lower != null && upper != null -> "conflicting"
        lower != null -> "at least"
        upper != null -> "at most"
        else -> "unknown"
    }
}
