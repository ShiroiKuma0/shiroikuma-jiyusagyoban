package com.opentasker.ui.charts

import com.opentasker.core.band.BandMetric

/**
 * Everything that distinguishes one line metric from another, as a table row.
 *
 * Adding a metric should be a row here, not new code. The gate values come from the charts hand-off,
 * which derived them from the band's real output; where a number is a judgement call rather than a
 * measurement, the comment says so.
 */
data class MetricSpec(
    val key: String,
    /** Japanese first, matching the rest of this fork's UI. */
    val label: String,
    val unit: String,
    /** Nominal sampling interval. Drives the gap threshold and the slew gate's per-step limit. */
    val cadenceSec: Int,
    val validMin: Double,
    val validMax: Double,
    /** When true a stored 0 means "no reading", not a measurement of zero. */
    val zeroIsNoReading: Boolean,
    /** Largest believable change across one cadence step. null disables the gate. */
    val slewPerStep: Double?,
    /** Hampel half-window in samples. 0 turns the filter off entirely. */
    val hampelHalfWindow: Int,
    val hampelSigmas: Double,
    /**
     * The floor under the MAD scale. MANDATORY where the filter is on.
     *
     * In a quiet window — seven SpO₂ readings of 97, resting HR pinned at 58 overnight — the MAD is
     * exactly 0, so the threshold is 0 and every deviation including a real one-unit change gets
     * flagged. The filter goes berserk in precisely the calmest, most trustworthy stretches of the
     * night. This floor is what stops that.
     */
    val hampelMinScale: Double,
    /** Fixed clinical band. Auto-expands to fit data, but never auto-ranges per window. */
    val yMin: Double,
    val yMax: Double,
    val decimals: Int,
    /**
     * Draw the axis from [yMin] with a visible break marker, because the true zero is far below and
     * a full-range axis would make real excursions invisible. Only SpO₂ needs this.
     */
    val axisBreak: Boolean = false,
) {
    /**
     * A gap is this many nominal cadences of silence. Default ×3 — 6 min for the 120 s metrics,
     * 30 min for SpO₂, 90 min for temperature.
     */
    fun gapThresholdMs(multiplier: Int): Long = cadenceSec * 1000L * multiplier

    fun format(value: Double): String = when (decimals) {
        0 -> value.toInt().toString()
        else -> String.format("%.${decimals}f", value)
    }
}

/**
 * The line metrics, in the order they stack in the 健康 tab.
 *
 * Steps, blood pressure and sleep are NOT here: they are not lines. Steps are bars from a
 * zero-inflated heavy-tailed series that must never be filtered, blood pressure is drawn as
 * unconnected dumbbells, and sleep stages are categorical.
 */
object MetricSpecs {

    /**
     * The 120-second periodic heart-rate series.
     *
     * NOT every `hr` row — see [ChartQualify.splitHeartRate]. The band interleaves a second,
     * differently-biased population into the same stream, and merging them produces a sawtooth that
     * would consume the Hampel filter's entire rejection budget.
     */
    val HEART_RATE = MetricSpec(
        key = BandMetric.HEART_RATE,
        label = "心拍",
        unit = "bpm",
        cadenceSec = 120,
        validMin = 25.0,
        validMax = 250.0,
        zeroIsNoReading = true,
        slewPerStep = 40.0,
        hampelHalfWindow = 3,
        hampelSigmas = 3.5,
        hampelMinScale = 2.0,
        yMin = 40.0,
        yMax = 180.0,
        decimals = 0,
    )

    val HRV = MetricSpec(
        key = BandMetric.HRV,
        label = "心拍変動",
        unit = "ms",
        cadenceSec = 120,
        validMin = 3.0,
        validMax = 400.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 3,
        hampelSigmas = 3.0,
        hampelMinScale = 3.0,
        yMin = 0.0,
        yMax = 150.0,
        decimals = 0,
    )

    /**
     * The band's RAW stress byte, deliberately not Hume's filtered number.
     *
     * Hume shows a filtered mean — their 37.2 against our raw 42.1 is best explained by dropping
     * records outside roughly HRV 19–75, but that was fitted against two targets and is a lead, not
     * a fact. Reproducing their number is separate work needing a second reference day.
     */
    val STRESS = MetricSpec(
        key = BandMetric.STRESS,
        label = "ストレス",
        unit = "",
        cadenceSec = 120,
        validMin = 0.0,
        validMax = 100.0,
        zeroIsNoReading = true,
        slewPerStep = null,
        hampelHalfWindow = 3,
        hampelSigmas = 3.0,
        hampelMinScale = 3.0,
        yMin = 0.0,
        yMax = 100.0,
        decimals = 0,
    )

    val SPO2 = MetricSpec(
        key = BandMetric.SPO2,
        label = "血中酸素",
        unit = "%",
        cadenceSec = 600,
        validMin = 70.0,
        validMax = 100.0,
        zeroIsNoReading = true,
        // NO slew gate. The charts hand-off proposed ">3 % SpO2" alongside ">40 bpm" and ">0.5 °C"
        // as limits on "a single 120 s step" — but SpO2 is sampled every TEN minutes, and over ten
        // minutes a swing of several points is ordinary physiology, not a sensor artefact. Measured
        // on 白い熊's own data, a 3-point limit flagged 53 of 430 adjacent pairs: 12.3 % of real
        // readings, in a series whose values run 91–100 with nothing out of range at all. Hampel is
        // the right instrument at this cadence.
        slewPerStep = null,
        hampelHalfWindow = 2,
        hampelSigmas = 3.0,
        hampelMinScale = 1.0,
        // 88–100 rather than 70–100: on a full axis a real desaturation is a couple of pixels. The
        // break marker at the baseline is what keeps the truncation honest instead of hidden.
        yMin = 88.0,
        yMax = 100.0,
        decimals = 0,
        axisBreak = true,
    )

    val TEMPERATURE = MetricSpec(
        key = BandMetric.TEMPERATURE,
        label = "体温",
        unit = "°C",
        cadenceSec = 1800,
        validMin = 30.0,
        validMax = 45.0,
        zeroIsNoReading = true,
        // Same reasoning as SpO2, more so: half a degree across THIRTY minutes is normal, and the
        // gate dropped four real readings on the reference data.
        slewPerStep = null,
        hampelHalfWindow = 2,
        hampelSigmas = 3.0,
        hampelMinScale = 0.15,
        yMin = 34.0,
        yMax = 38.0,
        decimals = 1,
    )

    /** Stacked in this order in the tab. */
    val LINES: List<MetricSpec> = listOf(HEART_RATE, HRV, SPO2, TEMPERATURE, STRESS)

    fun byKey(key: String): MetricSpec? = LINES.firstOrNull { it.key == key }
}
