package com.opentasker.ui.charts

import java.time.LocalDate
import java.time.ZoneId

/**
 * One row per day.
 *
 * The charts answer "what happened during the day"; this answers "**was Tuesday better or worse than
 * Monday**" — a different question, and the one worth acting on. A wiggly line across six days is bad
 * at it, and a single 健康指数 in isolation means very little where thirty of them in a column show
 * whether anything is actually moving.
 *
 * Everything here is a statistic over a **calendar day in the device's own zone**, not a rolling
 * window: comparing days requires days.
 *
 * Pure Kotlin apart from `java.time`, which is not an Android import — so the aggregation is testable
 * without a device, like everything else that decides something.
 */

/** What one day is worth, in the five or six numbers a person would actually compare. */
data class DaySummary(
    val date: LocalDate,
    /** 5th percentile of heart rate during that night's sleep, bpm. */
    val restingHr: Double?,
    /** Total minutes of the sleep session belonging to that night. */
    val sleepMinutes: Int?,
    val deepMinutes: Int?,
    val remMinutes: Int?,
    val steps: Int,
    /** 5th percentile of SpO₂ that day, %. */
    val spo2Low: Double?,
    /** The index computed from that day alone, or null when nothing that day could be scored. */
    val index: HealthIndexResult?,
) {
    val hasAnything: Boolean
        get() = restingHr != null || sleepMinutes != null || steps > 0 || spo2Low != null
}

object DailySummary {

    /**
     * Build the per-day rows, newest first.
     *
     * A night is attributed to the day it **started**, matching the band's own noon-to-noon chunking
     * and ordinary usage — "Tuesday's sleep" is the night you went to bed on Tuesday, even though
     * most of it happened on Wednesday.
     */
    /**
     * How much of the index has to be measured before a day earns a score in the table.
     *
     * A day where the band recorded nothing but a few hundred steps would otherwise score 0 out of
     * 100 — arithmetically correct, renormalised over the one component present, and completely
     * misleading sitting in a column beside days scored from all five. Half the weight is the line:
     * above it the number is comparable, below it the day shows a dash and the row still carries its
     * raw figures.
     */
    const val MIN_WEIGHT_FOR_A_ROW = 0.5

    fun build(
        hr: List<ChartPoint>,
        spo2: List<ChartPoint>,
        steps: List<ChartPoint>,
        sleepSessions: List<SleepSession>,
        spo2Times: Set<Long>,
        zone: ZoneId,
        limit: Int = 60,
    ): List<DaySummary> {
        fun dayOf(tMs: Long): LocalDate =
            java.time.Instant.ofEpochMilli(tMs).atZone(zone).toLocalDate()

        val hrByDay = hr.groupBy { dayOf(it.tMs) }
        val spo2ByDay = spo2.groupBy { dayOf(it.tMs) }
        val stepsByDay = steps.groupBy { dayOf(it.tMs) }
        // A night belongs to the day it started on, so its stats land beside that day's activity.
        val sleepByDay = sleepSessions.groupBy { dayOf(it.startMs) }

        val days = (hrByDay.keys + spo2ByDay.keys + stepsByDay.keys + sleepByDay.keys)
            .distinct().sortedDescending().take(limit)

        return days.map { day ->
            // One night per day: the longest, so a short nap never displaces the actual night.
            val night = sleepByDay[day]?.maxByOrNull { it.totalMinutes }
            val window = night?.let { it.startMs..it.endMs }
            val hrAll = hrByDay[day].orEmpty()
            val hrAsleep = window?.let { w -> hr.filter { it.tMs in w } }.orEmpty()
            val periodicAsleep = hrAsleep.filter { it.tMs !in spo2Times }
            val spo2Day = spo2ByDay[day].orEmpty()
            val dayStepPoints = stepsByDay[day].orEmpty()

            val restingHr = HealthIndexSource.percentile(hrAsleep.map { it.value }, 0.05)
            val spo2Low = HealthIndexSource.percentile(spo2Day.map { it.value }, 0.05)

            DaySummary(
                date = day,
                restingHr = restingHr,
                sleepMinutes = night?.totalMinutes,
                deepMinutes = night?.deep,
                remMinutes = night?.rem,
                steps = dayStepPoints.sumOf { it.value }.toInt(),
                spo2Low = spo2Low,
                index = HealthIndex.compute(
                    HealthIndexInputs(
                        restingHr = restingHr,
                        hrIqr = HealthIndexSource.iqr(periodicAsleep.map { it.value }),
                        spo2Low = spo2Low,
                        sleepMinutes = night?.totalMinutes,
                        deepRemShare = night?.deepRemShare,
                        // That day's OWN total, not the rolling window the dashboard index uses —
                        // a per-day row must be scoreable from that day alone.
                        steps = dayStepPoints.takeIf { it.isNotEmpty() }?.sumOf { p -> p.value },
                    ),
                ).takeIf { it.value != null && it.availableWeight >= MIN_WEIGHT_FOR_A_ROW },
            )
        }.filter { it.hasAnything }
    }
}
