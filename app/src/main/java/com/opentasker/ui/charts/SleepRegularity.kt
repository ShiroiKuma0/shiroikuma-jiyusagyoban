package com.opentasker.ui.charts

/**
 * The Sleep Regularity Index — the best-evidenced thing this band can compute, and it needs nothing
 * but sleep and wake times.
 *
 * ## Why it earns a place ahead of almost everything else
 *
 * In 60 977 UK Biobank participants wearing accelerometers, **sleep regularity predicted mortality
 * more strongly than sleep duration did**: against the least regular fifth, the four more regular
 * quintiles carried 20–48 % lower all-cause mortality risk, and the association survived adjustment
 * for duration (Windred et al. 2024, *Sleep* 47(1):zsad253). Duration is the metric everyone shows;
 * regularity is the one that turned out to matter more, and it is free from data we already store.
 *
 * It is also robust in a way the rest of this file's inputs are not. It uses only the sleep/wake
 * distinction — which consumer wearables get right, at 91–96 % sensitivity — and never the stage
 * labels, which they do not (κ 0.20–0.53).
 *
 * ## The definition, exactly
 *
 * Phillips et al. (2017): compare the sleep/wake state of every minute with the state of the same
 * minute 24 hours later, and score the fraction that agree.
 *
 * ```
 * SRI = 200 × (fraction of matched epochs) − 100
 * ```
 *
 * So **100 = perfectly regular** (asleep and awake at identical clock times every day), **0 = no
 * better than chance**, and negative means actively anti-phase. A person who sleeps 23:00–07:00 every
 * night scores near 100 however long they sleep; someone averaging the same eight hours but scattered
 * across the clock scores far lower. That is the distinction duration cannot make and this can.
 *
 * ## What it is NOT
 *
 * Not bedtime variability, and not a standard deviation of onset times. Those are cheaper and are
 * what most apps show; they are also not what the mortality evidence was built on. The epoch-matching
 * definition captures naps, fragmentation and shift patterns that an onset-time SD is blind to.
 *
 * Pure Kotlin over a minute-resolution boolean series, so the whole thing is JVM-testable.
 */
object SleepRegularity {

    const val MINUTE_MS = 60_000L
    const val DAY_MINUTES = 24 * 60

    /**
     * Below this there is not enough overlap for the index to mean anything.
     *
     * Each day contributes 1 440 comparisons but only against the day that follows it, so n nights
     * give n−1 comparable days. Windred used a week as the minimum; seven days is the floor here too.
     */
    const val MIN_DAYS = 7

    /**
     * SRI over a minute-resolution asleep/awake series.
     *
     * [asleep] maps an absolute minute index (epoch millis / 60 000) to true when 白い熊 was asleep.
     * Minutes absent from the map are treated as **awake**, which is the correct default: the band
     * records sleep, so an absence of a sleep record is an absence of sleep. The alternative — dropping
     * unknown minutes — would silently score a day of missing data as perfectly regular.
     */
    fun index(asleep: Set<Long>, fromMinute: Long, toMinute: Long): Double? {
        val span = toMinute - fromMinute
        if (span < MIN_DAYS * DAY_MINUTES) return null
        var matched = 0L
        var compared = 0L
        var m = fromMinute
        while (m + DAY_MINUTES <= toMinute) {
            if ((m in asleep) == ((m + DAY_MINUTES) in asleep)) matched++
            compared++
            m++
        }
        if (compared == 0L) return null
        return 200.0 * matched / compared - 100.0
    }

    /** Build the minute set from stitched sessions — every non-awake minute of every session. */
    fun asleepMinutes(sessions: List<SleepSession>): Set<Long> {
        val out = HashSet<Long>()
        for (s in sessions) {
            for (run in s.runs) {
                if (run.code == '5') continue
                var t = run.startMs
                while (t < run.endMs) {
                    out += t / MINUTE_MS
                    t += MINUTE_MS
                }
            }
        }
        return out
    }

    /**
     * The whole computation, from sessions.
     *
     * The window runs from the first recorded minute to the last, so it grows as history does; the
     * evidence used a week of accelerometry and there is no published upper bound.
     */
    fun of(sessions: List<SleepSession>): Double? {
        if (sessions.isEmpty()) return null
        val asleep = asleepMinutes(sessions)
        if (asleep.isEmpty()) return null
        val from = sessions.minOf { it.startMs } / MINUTE_MS
        val to = sessions.maxOf { it.endMs } / MINUTE_MS
        return index(asleep, from, to)
    }

    /**
     * The quintile bands from the UK Biobank cohort, so a number can be read without a table.
     *
     * Windred's quintile boundaries are approximately 71.6 / 77.7 / 81.8 / 85.6 across that
     * population, with the most regular quintile above 85.6 and the least below 71.6.
     */
    fun band(sri: Double): RegularityBand = when {
        sri >= 85.6 -> RegularityBand.VERY_REGULAR
        sri >= 77.7 -> RegularityBand.REGULAR
        sri >= 71.6 -> RegularityBand.MIDDLING
        else -> RegularityBand.IRREGULAR
    }
}

enum class RegularityBand { IRREGULAR, MIDDLING, REGULAR, VERY_REGULAR }
