package com.opentasker.ui.charts

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Day-by-day history, printed under every full-screen chart.
 *
 * A chart shows a shape; it does not let you read Tuesday's number. Zooming to a day and squinting at
 * the axis is arithmetic the page should be doing, and until now the sleep screen printed **only the
 * most recent night** with no date on it at all — so a night's figures could not be told from
 * yesterday's without remembering which was on screen (白い熊, 2026-08-07).
 *
 * Two shapes, because the metrics divide into two:
 *
 * - [MetricDay] — a day of a continuous measurement: its low, its high, its median, and how many
 *   readings that came from. The count is not decoration: a "median 68 bpm" built from four readings
 *   and one built from four hundred are different claims, and only one of them is worth acting on.
 * - [SleepNight] — a night, with the **extent** (`22:41 → 08:33`) as well as the duration, because
 *   "9h 52m" and "when" are separate facts and the band records both.
 *
 * Steps get [MetricDay] too, but their day figure is the **total**, not the median — a per-minute
 * count is a rate and the thing anyone wants from a day of it is the sum.
 *
 * Pure Kotlin apart from `java.time`, so the aggregation is testable without a device.
 */

/** One calendar day of one metric. */
data class MetricDay(
    val date: LocalDate,
    val lo: Double,
    val hi: Double,
    val median: Double,
    /** Sum of the day's values — meaningful for counts (steps), meaningless for rates. */
    val total: Double,
    val samples: Int,
)

/** One night, as the band recorded it. */
data class SleepNight(
    val date: LocalDate,
    val startMs: Long,
    val endMs: Long,
    val totalMinutes: Int,
    val deep: Int,
    val light: Int,
    val rem: Int,
    val awake: Int,
) {
    fun pctOf(minutes: Int): Int = if (totalMinutes > 0) minutes * 100 / totalMinutes else 0
}

object MetricHistory {

    /**
     * Bucket a series into calendar days in the device's own zone, newest first.
     *
     * Calendar days rather than rolling windows for the same reason the day table uses them:
     * comparing days requires days.
     */
    fun days(points: List<ChartPoint>, zone: ZoneId, limit: Int = 90): List<MetricDay> {
        if (points.isEmpty()) return emptyList()
        return points
            .groupBy { Instant.ofEpochMilli(it.tMs).atZone(zone).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .take(limit)
            .map { (date, dayPoints) ->
                val values = dayPoints.map { it.value }.sorted()
                MetricDay(
                    date = date,
                    lo = values.first(),
                    hi = values.last(),
                    median = values[values.size / 2],
                    total = values.sum(),
                    samples = values.size,
                )
            }
    }

    /**
     * One row per night, newest first.
     *
     * A night is filed under the day it **started**, matching [DailySummary] and the band's own
     * noon-to-noon chunking — see that file for why. Two sessions starting on the same day (a nap and
     * a night) both get a row here rather than the longest winning: this is a history, not a summary,
     * and dropping a real recorded session because another one was longer would be the chart
     * pretending it did not happen.
     */
    fun nights(sessions: List<SleepSession>, zone: ZoneId, limit: Int = 90): List<SleepNight> =
        sessions
            .sortedByDescending { it.startMs }
            .take(limit)
            .map { s ->
                SleepNight(
                    date = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate(),
                    startMs = s.startMs,
                    endMs = s.endMs,
                    totalMinutes = s.totalMinutes,
                    deep = s.deep,
                    light = s.light,
                    rem = s.rem,
                    awake = s.awake,
                )
            }
}
