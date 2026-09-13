package com.opentasker.ui.charts

import kotlin.math.abs

/**
 * The per-night series behind each row of 「平常との差」, so every row can be opened.
 *
 * 白い熊, 2026-09-13: *"make all the items, like HRV, Breathing rate — clickthrough, to their own
 * pages with graphs history etc."* A row on the strip is one night's value beside a baseline; the
 * page behind it is the same quantity for every night on record.
 *
 * ## Why these do not go through the charts pipeline
 *
 * [MetricSpec] describes a RAW SAMPLE SERIES and carries the machinery for cleaning one — a Hampel
 * filter, a slew gate, a cadence that decides what counts as a gap. Everything here is already a
 * derived value, one per night: a median, a total, a count of minutes. Running a slew gate over
 * nightly sleep minutes would apply a sensor correction to an arithmetic result, and a cadence gate
 * would call a night 白い熊 did not wear the band a data loss rather than a night they did not wear
 * the band. So these are their own thing, deliberately, and `MarkerHistoryScreen` draws them.
 *
 * Pure Kotlin, no Android — JVM-testable.
 */
object MarkerHistory {

    /** One night's value for one marker. [endMs] is the morning it is filed under. */
    data class Night(val endMs: Long, val value: Double)

    /** Everything the page behind a row needs. */
    data class Series(
        val marker: RecoveryMarker,
        /** Oldest first. Nights the marker was not measured on are absent, never zero-filled. */
        val nights: List<Night>,
        /** The banded reading for the latest night — the same one the row shows. */
        val latest: MarkerReading?,
    ) {
        val values: List<Double> get() = nights.map { it.value }

        /**
         * The best nights on record for this marker, best first.
         *
         * "Best" follows the marker's own direction — more sleep is better, a lower resting heart
         * rate is better — using the same table [MarkerReading.scaleStep] does, so the page and the
         * row can never disagree about which way is up.
         */
        fun best(n: Int): List<Night> {
            val higherIsBetter = when (marker) {
                RecoveryMarker.SLEEP, RecoveryMarker.DEEP, RecoveryMarker.DEEP_REM,
                RecoveryMarker.HRV, RecoveryMarker.HR_SWING -> true
                RecoveryMarker.NOCTURNAL_HR, RecoveryMarker.BEDTIME_HR, RecoveryMarker.TEMPERATURE,
                RecoveryMarker.FELT, RecoveryMarker.RESPIRATION -> false
            }
            return nights.sortedBy { if (higherIsBetter) -it.value else it.value }.take(n)
        }
    }

    /**
     * The night heart rate's usual descent — where it starts, where it bottoms out, and the drop.
     *
     * 白い熊 asked the curve's commentary to say *"what the usual drop from where has been"*, which
     * a swing alone cannot: 14 bpm of swing starting from 78 and 14 starting from 64 are different
     * nights. Both ends are needed, so both are carried.
     *
     * Medians rather than means, and of the WHOLE history rather than a trailing window: this is the
     * sentence's "usually", and a reader comparing last night to it expects 白い熊's normal, not the
     * normal of the last seven days — which last night may itself have helped set.
     */
    data class Descent(val from: Double, val to: Double) {
        val drop: Double get() = from - to
    }

    fun descent(bedtime: List<Double>, floor: List<Double>): Descent? {
        val f = HealthIndexSource.percentile(bedtime, 0.5) ?: return null
        val t = HealthIndexSource.percentile(floor, 0.5) ?: return null
        return Descent(f, t)
    }

    /**
     * How last night's descent compares with the usual one, as a sentence's worth of facts.
     *
     * [shallower] is the honest framing: a night can drop less because it started lower, because it
     * bottomed out higher, or both, and the three read very differently. The caller prints the
     * numbers and lets 白い熊 see which it was.
     */
    data class DescentComparison(
        val lastNight: Descent,
        val usual: Descent,
    ) {
        val dropDelta: Double get() = lastNight.drop - usual.drop
        val shallower: Boolean get() = dropDelta < 0
        /** True when the difference is too small to be worth a sentence at all. */
        val negligible: Boolean get() = abs(dropDelta) < MEANINGFUL_DROP_BPM
    }

    /**
     * Smallest difference in the nightly drop worth remarking on, in bpm.
     *
     * The heart rate's own published smallest-worthwhile-change — [Recovery.HR_MEANINGFUL_BPM] — and
     * not a figure invented here. A drop is a difference of two heart rates, so if neither end moved
     * meaningfully then neither did the drop.
     */
    const val MEANINGFUL_DROP_BPM = Recovery.HR_MEANINGFUL_BPM
}
