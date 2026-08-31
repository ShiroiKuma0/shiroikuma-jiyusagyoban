package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.compare.CompareData.Grain
import com.opentasker.ui.charts.compare.CompareData.Join
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What can honestly be said about two bands that disagree.
 *
 * Everything here is a statement about DIFFERENCES. Nothing averages the two devices together, and
 * nothing here is reported without the count it rests on — a median of three pairs and a median of
 * three hundred read identically on screen and mean entirely different things.
 */
object CompareStats {

    /**
     * The spread of Huawei-minus-Hume across the paired cells.
     *
     * **Median and quartiles, never a mean.** One band dropping out for a minute produces a delta of
     * the whole heart rate, and a single such reading drags a mean somewhere no pair of readings
     * ever was. The median is unmoved by it, and the quartiles say how wide the disagreement is
     * without pretending it is symmetric.
     */
    data class Delta(
        val pairs: Int,
        val median: Double?,
        val q1: Double?,
        val q3: Double?,
        /** How many pairs agree within [threshold], and what that threshold was. */
        val within: Int,
        val threshold: Double,
    ) {
        /** The agreement figure NEVER travels without its threshold — the number alone means nothing. */
        val agreement: String
            get() = if (pairs == 0) "—" else "$within/$pairs within ±${fmt(threshold)}"
    }

    fun delta(join: Join, threshold: Double): Delta {
        val deltas = join.cells.mapNotNull { it.delta }.sorted()
        if (deltas.isEmpty()) return Delta(0, null, null, null, 0, threshold)
        return Delta(
            pairs = deltas.size,
            median = quantile(deltas, 0.5),
            q1 = quantile(deltas, 0.25),
            q3 = quantile(deltas, 0.75),
            within = deltas.count { abs(it) <= threshold },
            threshold = threshold,
        )
    }

    /**
     * How far apart the two bands' clocks appear to be, or null when it cannot be said.
     *
     * **Measured and reported, never silently applied.** A comparison that quietly shifted one band
     * to fit the other would make every later disagreement smaller than it is, and would hide the
     * one fault this whole screen exists to reveal.
     *
     * Measured on STEPS at ten-minute grain, not on heart rate. A step count has sharp edges — a walk
     * starts and stops — while a heart rate lags physiologically, so measuring on it would fold
     * sensor response into clock skew and call the result an offset.
     *
     * Returns null below [minPairs]: an offset from two coincidences is a coincidence.
     */
    fun clockOffsetSeconds(join: Join, minPairs: Int = 6): Long? {
        if (join.grain == Grain.MINUTE) return null
        val moving = join.cells.filter { it.hasBoth && (it.huawei!! > 0.0 || it.hume!! > 0.0) }
        if (moving.size < minPairs) return null

        // The lag that best lines the two step profiles up, searched over whole bins. Anything finer
        // would be inventing precision the grain does not have.
        val width = join.grain.seconds
        var best = 0L
        var bestScore = Double.MAX_VALUE
        for (shift in -3..3) {
            val score = moving.sumOf { cell ->
                val other = join.cells.firstOrNull { it.epochMs == cell.epochMs + shift * width * 1000L }
                val hume = other?.hume ?: return@sumOf 0.0
                abs((cell.huawei ?: 0.0) - hume)
            }
            if (score > 0.0 && score < bestScore) { bestScore = score; best = shift * width }
        }
        return best.takeIf { it != 0L }
    }

    /**
     * The three footer lines, in order, never merged.
     *
     * Line three counts PAIRS, not samples, and reconciles with the two above it: `both + humeOnly`
     * is the Hume count and `both + huaweiOnly` the Huawei one. A reader who checks that arithmetic
     * has proved for themselves that nothing was pooled — which is the claim this screen makes and
     * the one it must be possible to verify.
     */
    fun footer(
        join: Join,
        delta: Delta,
        unit: String,
        scale: String,
        offsetSeconds: Long?,
    ): List<String> {
        val third = buildString {
            append("対 Pairs·${join.both} both ·${join.huaweiOnly} Band 11 only ·${join.humeOnly} Hume only")
            if (join.grain == Grain.MINUTE) append(" ·±${CompareJoin.PAIR_TOLERANCE_MS / 1000} s")
            if (join.notCounted > 0) append(" ·${join.notCounted} not counted")
            if (delta.pairs > 0) {
                append(" ·Δ median ${signed(delta.median)} $unit")
                append(" (IQR ${fmt(delta.q1)}–${fmt(delta.q3)})")
                append(" ·${delta.agreement} $unit")
            }
        }
        return listOf(
            "Band 11 ·${join.huaweiSamples} samples" +
                if (join.impossible > 0) " ·${join.impossible} impossible timestamps" else "",
            "Hume ·${join.humeSamples} samples",
            third,
            // The shared scale is stated outright: a stacked comparison lives or dies on both tracks
            // being drawn against the same numbers, and a reader cannot check that by eye.
            "同一目盛り same scale $scale ·offset " +
                (offsetSeconds?.let { "${it} s (measured, not applied)" } ?: "0 s (未測定)"),
        )
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    private fun signed(v: Double?): String =
        v?.let { (if (it >= 0) "+" else "") + fmt(it) } ?: "—"

    private fun fmt(v: Double?): String = when {
        v == null -> "—"
        abs(v - v.roundToLong()) < 0.05 -> v.roundToLong().toString()
        else -> "%.1f".format(v)
    }
}
