package com.opentasker.ui.charts.compare

/**
 * The types the two bands' comparison is built from.
 *
 * ## The rule this whole package exists to enforce
 *
 * From `HuaweiDao`: the two devices' metrics are NOT interchangeable and must never be pooled.
 * **Comparison means putting them side by side, never averaging across them.** Nothing here produces
 * a value derived from both devices except an explicit, signed DIFFERENCE — and a difference of two
 * readings is not a pooled measurement, it is a statement about disagreement.
 *
 * That is why there is no "combined" series type, no mean, and no interpolation of one band onto the
 * other's timestamps. A minute only one band saw stays a minute only one band saw.
 */
object CompareData {

    /** Which wrist a reading came from. Never encoded as a colour — see the compare screen. */
    enum class Device { HUAWEI, HUME }

    /**
     * What an ABSENT reading means for a metric on a device.
     *
     * This is the single most dangerous thing about comparing two bands, and it is why the field is
     * data rather than an assumption. A per-minute step count that is missing can mean "the wearer
     * took no steps" or "nothing was measured", and treating one as the other silently converts a
     * night's rest into missing data, or a dead sensor into a very still person.
     *
     * Measured on 白い熊's own devices, 2026-08-23: **both bands drop zeros.** The Hume parser skips
     * a per-minute count of 0 outright (`BandRecords.parseDetail`), and the Huawei band omits a field
     * it has nothing to report for — its ten-hour overnight "gap" in steps was 白い熊 asleep, with
     * heart rate present throughout to prove the band was recording. The earlier design assumed the
     * two conventions were OPPOSITE and refused per-minute step comparison outright; they are not.
     *
     * The refusal stays in the code anyway, because the assumption that matters is not "they agree"
     * but "we checked". A firmware change on either side that starts emitting zeros makes the
     * comparison wrong in a way no chart would reveal.
     */
    enum class ZeroConvention {
        /** Silence means the quantity was zero. Both bands, for per-minute counts. */
        ABSENT_IS_ZERO,

        /** Silence means nothing was measured. Both bands, for heart rate and SpO₂. */
        ABSENT_IS_UNMEASURED,
    }

    /**
     * How finely two series are lined up.
     *
     * The distinction is not cosmetic and not a preference:
     *
     * - [MINUTE] pairs individual readings by nearest time. Valid for INTENSIVE quantities — a heart
     *   rate, a blood-oxygen percentage — which describe an instant and cannot be added up.
     * - [TEN_MINUTES] and [DAY] sum readings into absolute-time bins. Valid only for EXTENSIVE
     *   quantities — steps, calories, distance — which are counts over a window and therefore add.
     *
     * **Summing a heart rate into a bin is meaningless**, and the join refuses to do it rather than
     * producing a number that looks like a chart.
     */
    enum class Grain(val seconds: Long) {
        MINUTE(60),
        TEN_MINUTES(600),
        DAY(86_400),
    }

    /** Whether a quantity adds over a window. Decides which grains are legal. */
    enum class Quantity {
        /** Steps, calories, distance: bins may sum. */
        EXTENSIVE,

        /** Heart rate, SpO₂: bins may not sum, ever. */
        INTENSIVE,
    }

    data class Reading(val epochMs: Long, val value: Double)

    /**
     * One device's readings for one metric, with the facts needed to compare them honestly.
     *
     * [key] is the chart key — `hw:` prefixed for the Huawei band — kept so a footer can say which
     * series it is describing without the caller threading a label alongside.
     */
    data class Series(
        val device: Device,
        val key: String,
        val quantity: Quantity,
        val zeroConvention: ZeroConvention,
        val readings: List<Reading>,
    )

    /**
     * One point of comparison.
     *
     * **A cell with only one band is a first-class result, not an absence.** It is the commonest
     * outcome — the two bands sample on different cadences — and rendering it as nothing would make
     * the comparison look far better than it is. The screen gives it its own mark and its own count.
     */
    data class Cell(
        val epochMs: Long,
        val huawei: Double?,
        val hume: Double?,
    ) {
        val hasBoth: Boolean get() = huawei != null && hume != null

        /**
         * Huawei minus Hume, or null when only one band was there.
         *
         * The ONLY quantity in this package derived from both devices, and it is a difference rather
         * than a pooling. Null when either side is missing — never zero, which would read as
         * "they agreed".
         */
        val delta: Double? get() = if (hasBoth) huawei!! - hume!! else null
    }

    /**
     * The join's answer, including everything a footer needs to reconcile.
     *
     * The counts are not decoration. `both + humeOnly` must equal the Hume readings that entered,
     * and `both + huaweiOnly` the Huawei ones — that identity is the reader's proof that nothing was
     * quietly dropped or double-counted, and it is asserted by a test.
     */
    data class Join(
        val grain: Grain,
        val cells: List<Cell>,
        val huaweiSamples: Int,
        val humeSamples: Int,
        val both: Int,
        val huaweiOnly: Int,
        val humeOnly: Int,
        /**
         * Readings thrown out before joining, and why. Counted rather than silently skipped: a
         * timestamp of exactly zero is what a spring-forward DST gap produces, and a reading outside
         * the requested window is what travel between zones produces. Both are real events on a real
         * wrist, and both would otherwise vanish.
         */
        val impossible: Int = 0,
        /** Bins that could not be complete, so they are drawn but excluded from any statistic. */
        val notCounted: Int = 0,
    )

    /**
     * Why a comparison was refused.
     *
     * A refusal is a result, not an error. It is shown where the reader looks for the comparison,
     * naming the metric and the reason — the alternative being a chart that is quietly wrong, which
     * is the outcome this whole package is arranged to prevent.
     */
    data class Refusal(val key: String, val reason: Reason, val detail: String) {
        enum class Reason {
            /** The two devices disagree about what an absent reading means. */
            ZERO_CONVENTION,

            /** A bin-summed grain was asked for on a quantity that does not add. */
            INTENSIVE_CANNOT_BIN,

            /** Neither band has anything in the window. */
            NO_DATA,
        }
    }

    sealed interface Result {
        data class Joined(val join: Join) : Result
        data class Refused(val refusal: Refusal) : Result
    }
}
