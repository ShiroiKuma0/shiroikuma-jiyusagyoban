package com.opentasker.ui.charts

import kotlin.math.abs

/**
 * S1 QUALIFY and S2 HAMPEL — the two stages that decide what is real.
 *
 * The order in the pipeline is not negotiable: filter BEFORE decimate. LTTB's selection criterion is
 * maximum triangle area, so it deliberately prefers extremes; decimate first and it picks out
 * precisely the outlier spikes. The surviving series is then outlier-enriched and non-uniformly
 * spaced, a rolling median computes over a sample that is mostly extremes and not contiguous in
 * time, the MAD inflates, the threshold widens, and the filter rejects nothing at all.
 */
object ChartQualify {

    /**
     * Split the `hr` stream into its two measurement populations.
     *
     * The band writes two different things into one stream: a 120-second periodic series, and an
     * extra reading taken at each SpO₂ measurement under a different measurement mode with a
     * different bias. Measured on 白い熊's own three days: the SpO₂-coincident population runs
     * **+7.46 bpm** high (mean 72.54 vs 65.08), and the median step across a series boundary is
     * 5.0 bpm against 2.0 bpm within a series. Merged, that is a systematic sawtooth every fifth
     * slot, and Hampel would spend its whole rejection budget on it.
     *
     * The charts hand-off proposed identifying them by their seconds field (`:14`/`:34` against the
     * periodic `:30`). That is a heuristic and it is wrong: of 433 interleaved samples only 322 sit
     * on `:14`/`:34`, so 111 would be misclassified. **Every one of the 433, however, shares its
     * exact timestamp with an SpO₂ sample** — that is the rule used here. It needs no schema change
     * and no tag at write time, because the join is exact.
     *
     * Neither population is discarded: the periodic series becomes the line, the SpO₂-coincident
     * readings are real measurements too and are returned for drawing as spot dots.
     */
    fun splitHeartRate(
        heartRate: List<ChartPoint>,
        spo2Times: Set<Long>,
    ): Pair<List<ChartPoint>, List<ChartPoint>> {
        val periodic = mutableListOf<ChartPoint>()
        val coincident = mutableListOf<ChartPoint>()
        for (p in heartRate) {
            if (p.tMs in spo2Times) coincident += p else periodic += p
        }
        return periodic to coincident
    }

    /**
     * Range gate, then slew gate, then Hampel.
     *
     * The range gate is non-statistical on purpose: sentinels and physiological impossibilities are
     * dropped before any statistic sees them. A blood-pressure 0 means "no reading" and breaks a
     * rolling median three ways — it drags the median down, it inflates the MAD, and with four or
     * more zeros in a seven-window the median BECOMES zero and the genuine readings get flagged as
     * the outliers. In the real capture 153 of 600 HRV records carried `hr=0, sbp=0, dbp=0`.
     */
    fun qualify(raw: List<ChartPoint>, spec: MetricSpec): QualifiedSeries {
        var noReading = 0
        var outOfRange = 0
        val kept = ArrayList<ChartPoint>(raw.size)

        for (p in raw) {
            if (spec.zeroIsNoReading && p.value == 0.0) {
                noReading++
                continue
            }
            if (p.value < spec.validMin || p.value > spec.validMax) {
                outOfRange++
                continue
            }
            kept += p
        }

        // Slew gate. A single cadence step of more than the limit is a sensor artefact by
        // physiology, not by statistics — the classic dropout spike, caught with an explicit
        // justification and no degenerate cases. Scaled by the actual elapsed time, so a sample
        // after a genuine gap is not condemned for being different from one an hour earlier.
        //
        // It FLAGS rather than deletes, for two reasons. A deleted sample is invisible: it would be
        // filed under "no-reading" in the footer, which is a lie — it WAS read. And 白い熊 must be
        // able to see exactly what the filter threw away, which is what the ✕ overlay is for.
        // It is still withheld from the Hampel statistics below, since a spike left in the window
        // inflates the MAD and lets the next spike through.
        val slewFlag = BooleanArray(kept.size)
        val limit = spec.slewPerStep
        if (limit != null && kept.size >= 2) {
            var previous = 0
            for (i in 1 until kept.size) {
                val dtSec = (kept[i].tMs - kept[previous].tMs) / 1000.0
                if (dtSec <= 0 || dtSec > spec.cadenceSec * 3.0) {
                    previous = i
                    continue
                }
                val allowed = limit * (dtSec / spec.cadenceSec).coerceAtLeast(1.0)
                if (abs(kept[i].value - kept[previous].value) > allowed) slewFlag[i] = true else previous = i
            }
        }

        // Hampel over the slew-cleaned subset, then merged back onto the full index space.
        val rejected = MutableList(kept.size) { slewFlag[it] }
        if (spec.hampelHalfWindow > 0) {
            val surviving = (kept.indices).filter { !slewFlag[it] }
            val flags = Hampel.flag(
                values = surviving.map { kept[it].value },
                halfWindow = spec.hampelHalfWindow,
                sigmas = spec.hampelSigmas,
                minScale = spec.hampelMinScale,
            )
            surviving.forEachIndexed { j, index -> if (flags[j]) rejected[index] = true }
        }

        return QualifiedSeries(kept, rejected, noReading, outOfRange)
    }
}

/**
 * Hampel: rolling median + median absolute deviation.
 *
 * Flags index `i` when `|v[i] − median(window)| > sigmas · max(1.4826 · MAD, minScale)`.
 *
 * It FLAGS. It never replaces. The textbook version substitutes the window median for the outlier,
 * which would draw a value that never occurred — and 白い熊's brief is that every drawn value be
 * traceable to a real measurement. The signature is the guarantee: values go in, booleans come out,
 * and there is no path by which a value could be written.
 */
object Hampel {

    private const val MAD_TO_SIGMA = 1.4826

    fun flag(
        values: List<Double>,
        halfWindow: Int,
        sigmas: Double,
        minScale: Double,
    ): List<Boolean> {
        require(minScale > 0.0) {
            "minScale must be positive: without a floor, a quiet window has MAD 0, so the threshold " +
                "is 0 and every real one-unit change is flagged"
        }
        if (halfWindow <= 0 || values.size < 3) return List(values.size) { false }

        val out = MutableList(values.size) { false }
        val window = DoubleArray(2 * halfWindow + 1)
        val deviations = DoubleArray(2 * halfWindow + 1)

        for (i in values.indices) {
            val from = (i - halfWindow).coerceAtLeast(0)
            val to = (i + halfWindow).coerceAtMost(values.lastIndex)
            val n = to - from + 1
            // A window truncated at the series ends is still usable, but two samples cannot
            // establish a spread — leave the edges alone rather than guessing.
            if (n < 3) continue

            for (j in 0 until n) window[j] = values[from + j]
            val median = medianOf(window, n)
            for (j in 0 until n) deviations[j] = abs(window[j] - median)
            val mad = medianOf(deviations, n)

            val scale = (MAD_TO_SIGMA * mad).coerceAtLeast(minScale)
            if (abs(values[i] - median) > sigmas * scale) out[i] = true
        }
        return out
    }

    /** Median of the first [n] entries of [scratch]. Sorts in place — the caller owns the array. */
    private fun medianOf(scratch: DoubleArray, n: Int): Double {
        java.util.Arrays.sort(scratch, 0, n)
        return if (n % 2 == 1) scratch[n / 2] else (scratch[n / 2 - 1] + scratch[n / 2]) / 2.0
    }
}
