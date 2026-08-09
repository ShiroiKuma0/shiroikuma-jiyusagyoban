package com.opentasker.ui.charts

import com.opentasker.core.band.BandMetric

/**
 * Turning loaded charts into [HealthIndexInputs].
 *
 * Kept apart from [HealthIndex] so the arithmetic stays free of any notion of where numbers come
 * from, and this file stays free of any notion of what they mean.
 *
 * The one rule throughout: **a component with no data is null, never zero and never a default.** A
 * night the band did not record must reach the index as "absent" so it can be reported as absent —
 * scoring it as a bad night is the failure mode this whole design exists to avoid.
 */
object HealthIndexSource {

    /** Percentile of a list, linear interpolation, 0.0..1.0. Null for an empty list. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val idx = (p.coerceIn(0.0, 1.0) * (sorted.size - 1))
        val lo = idx.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    fun median(values: List<Double>): Double? = percentile(values, 0.5)

    /** Inter-quartile range — a spread that a couple of outliers cannot inflate. */
    fun iqr(values: List<Double>): Double? {
        if (values.size < 4) return null
        val q1 = percentile(values, 0.25) ?: return null
        val q3 = percentile(values, 0.75) ?: return null
        return q3 - q1
    }

    fun compute(
        metrics: List<MetricChart>,
        latestSleep: SleepSession?,
        spo2Times: Set<Long>,
    ): HealthIndexResult {
        val hrPoints = pointsOf(metrics, BandMetric.HEART_RATE)
        val spo2Points = pointsOf(metrics, BandMetric.SPO2)

        // Resting HEART RATE is taken from the last sleep session, which is what "resting" means.
        // Taking a daytime minimum instead would reward sitting still, not cardiovascular fitness.
        //
        // It also makes the component immune to exercise, which is the right answer twice over: a
        // walk cannot cost score for raising the heart rate it is supposed to raise, and the effect
        // exercise DOES have on this number — a hard day showing up as a higher resting rate the
        // following night — is real physiology and belongs in the score.
        val window = latestSleep?.let { it.startMs..it.endMs }
        val hrAsleep = within(hrPoints, window)

        // Stability uses the PERIODIC population only — one measurement mode, so the spread measures
        // the heart rather than the interleaving. (The older reason given here, that the coincident
        // readings "run +7.46 bpm high", was wrong: asleep the two agree to 1 bpm. They diverge with
        // MOVEMENT, of which there is none in this window. See docs/hume-band-protocol.md §2.)
        val periodicAsleep = hrAsleep.filter { it.tMs !in spo2Times }

        return HealthIndex.compute(
            HealthIndexInputs(
                restingHr = percentile(hrAsleep.map { it.value }, 0.05),
                hrIqr = iqr(periodicAsleep.map { it.value }),
                spo2Low = percentile(spo2Points.takeLast(SPO2_WINDOW).map { it.value }, 0.05),
                sleepMinutes = latestSleep?.totalMinutes,
                deepRemShare = latestSleep?.deepRemShare,
                steps = stepsLastDay(metrics),
            ),
        )
    }

    /**
     * The last day's step total, or null when the band has no step data at all.
     *
     * The null/zero distinction is the whole subtlety. Everywhere else in this file an absent
     * measurement means the band did not measure; for steps, **zero is a measurement** — a day of
     * sitting still is a real and rather informative reading. So an empty series is null and a series
     * of zeroes is 0.0, and only the first is reported as missing.
     */
    fun stepsLastDay(metrics: List<MetricChart>): Double? {
        val points = pointsOf(metrics, BandMetric.STEPS_MINUTE)
        if (points.isEmpty()) return null
        return lastDay(points).sumOf { it.value }
    }

    private fun pointsOf(metrics: List<MetricChart>, key: String): List<ChartPoint> {
        val chart = metrics.firstOrNull { it.spec.key == key } ?: return emptyList()
        // The retained samples, so a flagged outlier cannot drag a component. Bars keep their raw
        // points because they are never filtered.
        return chart.chunk?.segments?.flatMap { it.points } ?: chart.bars
    }

    private fun within(points: List<ChartPoint>, window: LongRange?): List<ChartPoint> =
        if (window == null) emptyList() else points.filter { it.tMs in window }

    /** The last 24 hours of a series, measured back from its own newest sample. */
    fun lastDay(points: List<ChartPoint>): List<ChartPoint> {
        val newest = points.maxOfOrNull { it.tMs } ?: return emptyList()
        val from = newest - DAY_MS
        return points.filter { it.tMs >= from }
    }

    const val DAY_MS = 24 * 3_600_000L

    /** Roughly a day of SpO₂ at a ten-minute cadence. */
    private const val SPO2_WINDOW = 144
}
