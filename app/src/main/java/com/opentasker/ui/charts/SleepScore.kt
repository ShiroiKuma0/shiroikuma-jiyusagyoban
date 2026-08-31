package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A sleep score with **published weights** — Apple's, which are the only ones any vendor states.
 *
 * ## Why Apple's and not one of our own
 *
 * Every other manufacturer ships a sleep score and none of them publishes how it is composed. Apple
 * does, in one sentence: *"Your sleep score is calculated based on estimates of sleep duration
 * (50 points), bedtime consistency (30 points), and interruptions (20 points)."* Bedtime consistency
 * looks at the last **13 nights**. The bands are 0–40 / 41–60 / 61–80 / 81–95 / 96+.
 *
 * Two things about that composition are worth copying rather than improving on:
 *
 * - **Sleep stages are not a component.** Apple has the best-validated staging of any consumer device
 *   in the independent literature (κ 0.68, against 0.20–0.53 for the rest) and still leaves stages
 *   out of its score. That is the same conclusion four literature reviews reached here, arrived at by
 *   a manufacturer with every commercial reason to show off its staging.
 * - **Consistency is worth 30 of 100.** More than most people expect, and consistent with the
 *   regularity evidence in [SleepRegularity] — the same finding, at a different time scale.
 *
 * ## What Apple does NOT publish, and is therefore ours
 *
 * The shape of each term inside its point budget. The curves below are stated here rather than hidden:
 * duration ramps to full marks at 7 h and holds (the AASM/NSF adult recommendation), consistency
 * decays linearly to zero at a 3 h deviation from the personal median onset, and interruptions decay
 * linearly to zero at 90 minutes awake. Each is a defensible reading of the published intent, and none
 * of them is a validated curve — which is why the score is shown beside the raw numbers, never alone.
 *
 * Pure Kotlin, no clock: the caller supplies the nights.
 */
object SleepScore {

    const val W_DURATION = 50.0
    const val W_CONSISTENCY = 30.0
    const val W_INTERRUPTIONS = 20.0

    /** Apple's window for bedtime consistency. */
    const val CONSISTENCY_NIGHTS = 13

    /** Full marks for duration at or above this. AASM/NSF adult guidance is 7–9 h. */
    const val TARGET_MINUTES = 7 * 60.0

    /** A deviation this far from the personal median onset scores zero for consistency. */
    const val CONSISTENCY_ZERO_MINUTES = 180.0

    /** This much time awake in the night scores zero for interruptions. */
    const val INTERRUPTIONS_ZERO_MINUTES = 90.0

    data class Breakdown(
        val total: Int,
        val duration: Double,
        val consistency: Double,
        val interruptions: Double,
        /** Minutes between last night's onset and the median of the previous nights. */
        val onsetDeviationMinutes: Double?,
    )

    /**
     * Score the most recent night.
     *
     * [previousOnsetsMinutesOfDay] are the clock-time onsets of the preceding nights, in minutes past
     * midnight — **not** epoch times, because consistency is about the clock, not the calendar.
     */
    fun score(
        asleepMinutes: Double,
        awakeMinutes: Double,
        onsetMinuteOfDay: Double?,
        previousOnsetsMinutesOfDay: List<Double>,
    ): Breakdown {
        val duration = W_DURATION * (asleepMinutes / TARGET_MINUTES).coerceIn(0.0, 1.0)
        val interruptions =
            W_INTERRUPTIONS * (1.0 - (awakeMinutes / INTERRUPTIONS_ZERO_MINUTES)).coerceIn(0.0, 1.0)

        // The median onset of the trailing window, compared on a CIRCULAR clock: 23:50 and 00:10 are
        // twenty minutes apart, not twenty-three hours and forty. Getting that wrong would punish
        // every night that happens to straddle midnight, which for 白い熊 is most of them.
        val window = previousOnsetsMinutesOfDay.takeLast(CONSISTENCY_NIGHTS)
        val deviation = if (onsetMinuteOfDay == null || window.isEmpty()) {
            null
        } else {
            val reference = circularMedian(window)
            circularDistance(onsetMinuteOfDay, reference)
        }
        val consistency = if (deviation == null) {
            0.0
        } else {
            W_CONSISTENCY * (1.0 - (deviation / CONSISTENCY_ZERO_MINUTES)).coerceIn(0.0, 1.0)
        }

        return Breakdown(
            total = (duration + consistency + interruptions).roundToInt().coerceIn(0, 100),
            duration = duration,
            consistency = consistency,
            interruptions = interruptions,
            onsetDeviationMinutes = deviation,
        )
    }

    /** Shortest distance between two clock times, in minutes: never more than 12 hours. */
    fun circularDistance(a: Double, b: Double): Double {
        val raw = abs(a - b) % 1440.0
        return minOf(raw, 1440.0 - raw)
    }

    /**
     * The clock time that minimises total circular distance to the sample.
     *
     * Brute-forced over the 1 440 minutes of the day rather than done with vectors: it is 1 440 × 13
     * comparisons once per refresh, and the closed form for a circular median is fiddly enough to get
     * wrong quietly.
     */
    fun circularMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var best = 0.0
        var bestCost = Double.MAX_VALUE
        for (m in 0 until 1440) {
            val cost = values.sumOf { circularDistance(it, m.toDouble()) }
            if (cost < bestCost) {
                bestCost = cost
                best = m.toDouble()
            }
        }
        return best
    }

    fun band(total: Int): SleepScoreBand = when {
        total >= 96 -> SleepScoreBand.VERY_HIGH
        total >= 81 -> SleepScoreBand.HIGH
        total >= 61 -> SleepScoreBand.OK
        total >= 41 -> SleepScoreBand.LOW
        else -> SleepScoreBand.VERY_LOW
    }
}

enum class SleepScoreBand { VERY_LOW, LOW, OK, HIGH, VERY_HIGH }
