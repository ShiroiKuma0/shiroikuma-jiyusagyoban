package com.opentasker.ui.charts

import kotlin.math.abs

/**
 * What last night's heart-rate curve did, in the few facts a sentence can honestly carry.
 *
 * ## Why a describer rather than a verdict
 *
 * 白い熊 asked for a commentary on the curve (2026-09-12). The temptation is to write one that
 * interprets — "a healthy descent", "a disturbed night" — and this file deliberately does not. A
 * night's heart-rate shape has no published normal range on a consumer band, this record holds
 * twenty-one nights, and the one night whose shape was genuinely remarkable was remarkable *after*
 * 白い熊 already knew they felt ill. So the commentary reports **where the low point fell, how far
 * the curve moved, and whether it was climbing by morning** — three things the picture shows and a
 * reader would otherwise have to squint to extract.
 *
 * Whether that is unusual *for 白い熊* is a separate question, already answered directly above the
 * chart by [RecoveryMarker.HR_SWING] banded against their own baseline. Saying it twice, once with a
 * number and once with an adjective, would make the adjective look like the finding.
 *
 * Pure Kotlin, no Android, no clock — JVM-testable.
 */
object HrCurveShape {

    /** Where in the night the curve reached its lowest point. */
    enum class Nadir { EARLY, MIDDLE, LATE }

    /**
     * How much of the swing the curve had climbed back by its final bin.
     *
     * Not a boolean, because "rose toward waking" is a claim about size as well as direction: a
     * curve that ends one beat above its floor has technically risen and has not done anything a
     * reader should be told about.
     */
    enum class Ending { RISING, FLAT_AT_THE_BOTTOM, STILL_FALLING }

    data class Shape(
        val nadir: Nadir,
        /** Highest bin minus lowest, in bpm — the same quantity [RecoveryMarker.HR_SWING] bands. */
        val swingBpm: Double,
        val ending: Ending,
        /** Where the low fell, as a fraction of the night. Carried so a caller can place a marker. */
        val nadirFraction: Double,
    )

    /** A final bin this far above the night's floor, as a share of the swing, counts as rising. */
    const val RISE_SHARE = 0.35

    /**
     * Below this swing, in bpm, the curve is too flat for its nadir's POSITION to mean anything.
     *
     * Set at 8.0 against 白い熊's own twenty-one nights, whose swings run
     * `6.0 6.5 8.5 9.5 9.5 9.5 10.0 11.0 12.0 12.5 12.5 13.5 14.0 14.0 14.5 15.0 16.0 16.0 16.5 17.0`.
     * Eight separates the two genuinely flat nights from everything else and leaves every ordinary
     * night describable.
     *
     * **It was 4.0 for one build and that was too low** — the flattest night on record, a 6 bpm span,
     * came out as "lowest early in the night", which is a claim about where a wobble happened. Bin
     * medians over a per-minute integer series carry a beat or two of noise on their own; a nadir
     * picked out of six is picked out of not much more than that. Below this the sentence reports
     * the flatness itself, which is the thing that is actually true about such a night.
     */
    const val FLAT_BPM = 8.0

    /**
     * Describe a binned curve, or null when there is not enough of one to describe.
     *
     * Null rather than a shrug: a two-bin "curve" has a lowest point by definition and saying where
     * it fell would be arithmetic dressed as observation.
     */
    fun describe(curve: List<Double>): Shape? {
        if (curve.size < 6) return null
        val lo = curve.min()
        val hi = curve.max()
        val swing = hi - lo
        val at = curve.indexOfFirst { it == lo }
        // The fraction uses bin CENTRES: with twelve bins, bin 0 is the first twelfth of the night,
        // not the instant it began, and calling it 0.0 would report a low at bed time.
        val frac = (at + 0.5) / curve.size
        val nadir = when {
            frac < 1.0 / 3 -> Nadir.EARLY
            frac < 2.0 / 3 -> Nadir.MIDDLE
            else -> Nadir.LATE
        }
        val climbed = curve.last() - lo
        val ending = when {
            swing <= 0.0 -> Ending.FLAT_AT_THE_BOTTOM
            climbed >= RISE_SHARE * swing -> Ending.RISING
            // Still within a beat of its floor at the end, having got there earlier.
            abs(climbed) < RISE_SHARE * swing && at < curve.size - 1 -> Ending.FLAT_AT_THE_BOTTOM
            else -> Ending.STILL_FALLING
        }
        return Shape(nadir, swing, ending, frac)
    }

    /** True when the curve is too flat for [Shape.nadir] to be worth reporting at all. */
    fun Shape.nadirIsMeaningful(): Boolean = swingBpm >= FLAT_BPM
}
