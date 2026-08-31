package com.opentasker.ui.charts.huawei

import com.opentasker.core.band.BandMetric
import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.charts.ChartPoint

/**
 * The years before the Huawei band existed, drawn from the Hume band's records.
 *
 * ## What this is, and what it is emphatically not
 *
 * It is NOT pooling. The two devices' readings never occupy the same instant here: the Hume band
 * supplies the era **before** the Huawei band's first reading and stops there, and from the cutover
 * onward the Huawei band is alone. Nothing is averaged, nothing is reconciled, and no minute holds a
 * value from both wrists. 白い熊's instruction, 2026-08-23: *"going back, where we have nothing in
 * Huawei, draw in the graphs the Hume history — and from now on we only show the Huawei."*
 *
 * That distinction is what makes it honest, and it is also fragile: the moment someone lets the two
 * overlap by a single day, every statistic downstream is quietly measuring two devices at once. So
 * the cutover is computed from the data rather than configured, and the prefix is truncated to it.
 *
 * ## Why the Hume readings are thinned
 *
 * The Hume band samples far more densely than the Huawei one. Drawn untouched, its era would be a
 * solid band of ink beside the Huawei era's scatter, and the chart would appear to say the older
 * data is better — when the difference is the instrument, not the measurement. So the prefix is
 * decimated to the Huawei band's own observed cadence: the shape of the history is preserved, the
 * apparent density does not lie.
 *
 * Thinning DROPS readings; it never averages them. Every point drawn is a reading that happened.
 */
object HuaweiHistory {

    /** How the two devices' metrics correspond, for the purpose of a historical prefix only. */
    private val EQUIVALENT = mapOf(
        HuaweiKeys.STEPS to BandMetric.STEPS_MINUTE,
        HuaweiKeys.HEART_RATE to BandMetric.HEART_RATE,
        HuaweiKeys.SPO2 to BandMetric.SPO2,
    )

    /**
     * One metric's history, and where the devices hand over.
     *
     * [cutoverMs] is null when the Huawei band has no reading of this metric at all — in which case
     * the whole span is Hume's, and the chart should say so.
     */
    data class Prefix(
        val points: List<ChartPoint>,
        val cutoverMs: Long?,
        val humeCount: Int,
    )

    /**
     * Fetch the Hume era for [huaweiKey].
     *
     * @param cadenceSec the Huawei band's own observed interval for this metric — the density the
     *   prefix is thinned to. Taken from the coverage card's measurement rather than assumed.
     */
    suspend fun prefix(
        db: AppDatabase,
        huaweiKey: String,
        fromMs: Long,
        cutoverMs: Long?,
        cadenceSec: Int,
    ): Prefix {
        val humeKey = EQUIVALENT[huaweiKey] ?: return Prefix(emptyList(), cutoverMs, 0)
        // A cutover of null means the Huawei band has nothing here, so the Hume era runs to now.
        val until = cutoverMs ?: System.currentTimeMillis()
        if (until <= fromMs) return Prefix(emptyList(), cutoverMs, 0)

        val rows = db.bandSampleDao().rangeAsc(humeKey, fromMs, until - 1)
        val thinned = thin(rows.map { ChartPoint(it.epochMs, it.value) }, cadenceSec * 1000L)
        return Prefix(thinned, cutoverMs, thinned.size)
    }

    /**
     * Keep at most one reading per [stepMs], the first in each window.
     *
     * The first rather than the nearest to some grid, because "first in the window" is a rule that
     * needs no interpolation and can pick nothing that was not recorded. A mean of the window would
     * be a value nobody measured, and this file's whole claim is that every point drawn is real.
     */
    fun thin(points: List<ChartPoint>, stepMs: Long): List<ChartPoint> {
        if (stepMs <= 0L || points.isEmpty()) return points
        val out = ArrayList<ChartPoint>(points.size)
        var nextAllowed = Long.MIN_VALUE
        for (p in points) {
            if (p.tMs >= nextAllowed) {
                out += p
                nextAllowed = p.tMs + stepMs
            }
        }
        return out
    }

    /**
     * Where the Huawei band's own record begins, across every metric it holds.
     *
     * One instant for the whole screen rather than one per card: a reader comparing two cards must
     * be able to trust that the same vertical line means the same thing on both, and per-metric
     * cutovers would put the handover in a different place on each.
     */
    suspend fun cutover(db: AppDatabase): Long? =
        db.huaweiSampleDao().oldestAny()?.let { it * 1000L }
}
