package com.opentasker.ui.charts

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The adaptive boundary markers — what 白い熊 asked for specifically:
 *
 * > Free panning, start with 24h, but both zoom in and out, marking visually the hour boundaries,
 * > when zoom smaller the day boundaries, theoretically with enough data showing only week
 * > boundaries etc.
 *
 * The ladder is chosen from the visible span, and the label format with it. Expressing zoom as a
 * span in milliseconds rather than a unitless scale factor is what makes this fall out cleanly —
 * "6 hours" picks its own ticks, "scale 4.31" does not.
 */

/** One boundary line. [major] draws more strongly and carries the label. */
data class ChartTick(val tMs: Long, val major: Boolean, val label: String)

enum class TickScale { MINUTE, HOUR, DAY, WEEK, MONTH }

object ChartTicks {

    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /**
     * Steps with [ZonedDateTime], never by adding fixed millis.
     *
     * A fixed-millis ladder drifts by an hour across a DST transition and the labels stop landing on
     * the hour — the ticks say 15:00 while sitting at 14:00. 白い熊 is in Europe/Prague, which has
     * two transitions a year, so this is a real bug and not a theoretical one.
     */
    fun forSpan(startMs: Long, endMs: Long, zone: ZoneId): List<ChartTick> {
        val span = endMs - startMs
        if (span <= 0) return emptyList()

        val scale = scaleFor(span)
        val ticks = mutableListOf<ChartTick>()
        val from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone)

        var cursor = floorTo(from, scale)
        var guard = 0
        while (cursor.toInstant().toEpochMilli() <= endMs && guard++ < 512) {
            val tMs = cursor.toInstant().toEpochMilli()
            if (tMs >= startMs) {
                ticks += ChartTick(tMs, major = isMajor(cursor, scale), label = labelFor(cursor, scale, span))
            }
            cursor = step(cursor, scale)
        }
        return ticks
    }

    fun scaleFor(spanMs: Long): TickScale = when {
        spanMs < 30 * MINUTE -> TickScale.MINUTE
        spanMs < 3 * HOUR -> TickScale.MINUTE
        spanMs < 36 * HOUR -> TickScale.HOUR
        spanMs < 10 * DAY -> TickScale.DAY
        spanMs < 70 * DAY -> TickScale.WEEK
        else -> TickScale.MONTH
    }

    /** The minor step. The major boundary is the next unit up, and draws more strongly. */
    private fun step(t: ZonedDateTime, scale: TickScale): ZonedDateTime = when (scale) {
        TickScale.MINUTE -> t.plusMinutes(minuteStep(t))
        TickScale.HOUR -> t.plusHours(1)
        TickScale.DAY -> t.plusHours(6)
        TickScale.WEEK -> t.plusDays(1)
        TickScale.MONTH -> t.plusWeeks(1)
    }

    private fun minuteStep(t: ZonedDateTime): Long = 5

    private fun floorTo(t: ZonedDateTime, scale: TickScale): ZonedDateTime = when (scale) {
        TickScale.MINUTE -> t.truncatedTo(ChronoUnit.HOURS)
            .plusMinutes((t.minute / 5).toLong() * 5)
        TickScale.HOUR -> t.truncatedTo(ChronoUnit.HOURS)
        TickScale.DAY -> t.truncatedTo(ChronoUnit.DAYS).plusHours((t.hour / 6).toLong() * 6)
        TickScale.WEEK -> t.truncatedTo(ChronoUnit.DAYS)
        TickScale.MONTH -> t.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    private fun isMajor(t: ZonedDateTime, scale: TickScale): Boolean = when (scale) {
        // At minute scale the hour is the strong line; at hour scale, midnight; and so on up.
        TickScale.MINUTE -> t.minute == 0
        TickScale.HOUR -> t.hour == 0
        TickScale.DAY -> t.hour == 0
        TickScale.WEEK -> t.dayOfWeek == DayOfWeek.MONDAY
        TickScale.MONTH -> t.dayOfMonth <= 7
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    /**
     * The one date format, everywhere (白い熊, 2026-08-07): `2026-08-07`, never `M/d`.
     *
     * It is wide for an axis, and [labelled] is what makes that affordable — it already thins labels
     * to at most eight majors, and `drawTimeLabels` drops any that would still overlap rather than
     * letting two dates collide. A dropped label is recoverable by zooming; a date misread because
     * the year was missing is not.
     */
    private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("M月")

    private fun labelFor(t: ZonedDateTime, scale: TickScale, spanMs: Long): String = when (scale) {
        TickScale.MINUTE -> t.format(HHMM)
        // At hour scale midnight gets the date instead of a redundant "00:00" — the day boundary is
        // the most useful thing to name on a 24-hour chart.
        TickScale.HOUR -> if (t.hour == 0) t.format(DAY_LABEL) else t.format(HHMM)
        TickScale.DAY -> if (t.hour == 0) t.format(DAY_LABEL) else t.format(HHMM)
        TickScale.WEEK -> t.format(DAY_LABEL)
        TickScale.MONTH -> if (t.dayOfMonth <= 7) t.format(MONTH_LABEL) else t.format(DAY_LABEL)
    }

    /** Only major ticks carry a label at dense scales, so labels never collide. */
    fun labelled(ticks: List<ChartTick>, maxLabels: Int = 5): List<ChartTick> {
        val majors = ticks.filter { it.major }
        if (majors.isEmpty()) return ticks
        val everyNth = (majors.size + maxLabels - 1) / maxLabels
        if (everyNth <= 1) return ticks
        val keep = majors.filterIndexed { i, _ -> i % everyNth == 0 }.map { it.tMs }.toSet()
        return ticks.map { if (it.major && it.tMs !in keep) it.copy(label = "") else it }
    }
}
