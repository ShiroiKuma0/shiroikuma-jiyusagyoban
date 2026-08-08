package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One row per day, and the two decisions that make it right.
 *
 * The first is **which day a night belongs to**. A night that starts at 23:40 on Tuesday and ends at
 * 07:10 on Wednesday is Tuesday's sleep, in the way everyone talks about sleep and the way the band's
 * own noon-to-noon chunking treats it. Attributing it to Wednesday puts it in a row beside
 * Wednesday's steps, which is a different night's rest against that day's activity — the comparison
 * the table exists for, silently misaligned by one row.
 *
 * The second is **refusing rather than guessing**. A day with no sleep record has no resting heart
 * rate, and the row says so instead of borrowing the previous night's.
 */
class DailySummaryTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun night(startY: Int, startM: Int, startD: Int, startH: Int, hours: Int): SleepSession {
        val start = at(startY, startM, startD, startH)
        val runs = (0 until hours * 2).map { i ->
            SleepRun(
                start + i * 30 * 60_000L,
                start + (i + 1) * 30 * 60_000L,
                if (i % 3 == 0) '1' else if (i % 3 == 1) '3' else '2',
            )
        }
        return SleepSession(start, start + hours * 3_600_000L, runs)
    }

    /** The whole reason the attribution rule is written down. */
    @Test
    fun `a night that crosses midnight belongs to the day it started`() {
        val days = DailySummary.build(
            hr = listOf(ChartPoint(at(2026, 8, 4, 23, 50), 55.0)),
            spo2 = emptyList(),
            steps = emptyList(),
            sleepSessions = listOf(night(2026, 8, 4, 23, 7)),
            spo2Times = emptySet(),
            zone = zone,
        )
        val tuesday = days.first { it.date == LocalDate.of(2026, 8, 4) }
        assertEquals(7 * 60, tuesday.sleepMinutes)
        assertTrue(days.none { it.date == LocalDate.of(2026, 8, 5) && it.sleepMinutes != null })
    }

    @Test
    fun `steps are summed within the calendar day and nowhere else`() {
        val days = DailySummary.build(
            hr = emptyList(),
            spo2 = emptyList(),
            steps = listOf(
                ChartPoint(at(2026, 8, 4, 9), 1200.0),
                ChartPoint(at(2026, 8, 4, 18), 3300.0),
                ChartPoint(at(2026, 8, 5, 9), 700.0),
            ),
            sleepSessions = emptyList(),
            spo2Times = emptySet(),
            zone = zone,
        )
        assertEquals(4500, days.first { it.date == LocalDate.of(2026, 8, 4) }.steps)
        assertEquals(700, days.first { it.date == LocalDate.of(2026, 8, 5) }.steps)
    }

    /** Resting heart rate is a *sleeping* statistic — no night, no number, and no borrowing. */
    @Test
    fun `a day with no sleep record reports no resting rate rather than borrowing one`() {
        val days = DailySummary.build(
            hr = listOf(ChartPoint(at(2026, 8, 6, 13), 88.0), ChartPoint(at(2026, 8, 6, 14), 92.0)),
            spo2 = emptyList(),
            steps = listOf(ChartPoint(at(2026, 8, 6, 12), 900.0)),
            sleepSessions = emptyList(),
            spo2Times = emptySet(),
            zone = zone,
        )
        val day = days.first { it.date == LocalDate.of(2026, 8, 6) }
        assertEquals(900, day.steps)
        assertNull(day.restingHr)
        assertNull(day.sleepMinutes)
        assertNull(
            "steps alone are 20 % of the index — too little to put a comparable number in the column",
            day.index,
        )
    }

    /**
     * The gate the steps component made necessary (2026-08-07).
     *
     * Steps score on their own, so a day with nothing but a short walk would otherwise land a
     * renormalised 0 in a column beside days scored from all five components. Arithmetically right,
     * and unreadable. Half the index's weight has to be present before a day gets a number.
     */
    @Test
    fun `a day scored from too little of the index shows no number at all`() {
        val start = at(2026, 8, 4, 23)
        val asleep = (0 until 400).map { ChartPoint(start + it * 60_000L, 54.0 + it % 4) }
        // Sleep + resting HR + stability = 0.63 of the weight: enough.
        val rich = DailySummary.build(
            hr = asleep, spo2 = emptyList(), steps = emptyList(),
            sleepSessions = listOf(night(2026, 8, 4, 23, 7)), spo2Times = emptySet(), zone = zone,
        ).first { it.date == LocalDate.of(2026, 8, 4) }
        assertTrue("expected a score, got ${rich.index}", rich.index?.value != null)

        // Steps alone = 0.20: not enough.
        val thin = DailySummary.build(
            hr = emptyList(), spo2 = emptyList(),
            steps = listOf(ChartPoint(at(2026, 8, 9, 12), 9_000.0)),
            sleepSessions = emptyList(), spo2Times = emptySet(), zone = zone,
        ).first { it.date == LocalDate.of(2026, 8, 9) }
        assertEquals(9_000, thin.steps)
        assertNull(thin.index)
    }

    /**
     * A day whose only readings are daytime heart rate gets **no row**.
     *
     * Every column in the table is either a sleeping statistic, a daily total, or a percentile that
     * needs a population; a day with two awake samples fills none of them, so the row would be five
     * dashes and a date. Dropping it is the difference between a table that shows the days worth
     * comparing and one padded with days that answer nothing.
     */
    @Test
    fun `a day that fills no column is dropped rather than shown as dashes`() {
        val days = DailySummary.build(
            hr = listOf(ChartPoint(at(2026, 8, 6, 13), 88.0), ChartPoint(at(2026, 8, 6, 14), 92.0)),
            spo2 = emptyList(),
            steps = emptyList(),
            sleepSessions = emptyList(),
            spo2Times = emptySet(),
            zone = zone,
        )
        assertTrue(days.isEmpty())
    }

    @Test
    fun `the resting rate comes from the sleeping window, not the whole day`() {
        val start = at(2026, 8, 4, 23)
        val asleep = (0 until 60).map { ChartPoint(start + it * 60_000L, 52.0 + it % 3) }
        val awake = (0 until 60).map { ChartPoint(at(2026, 8, 4, 15) + it * 60_000L, 95.0) }
        val days = DailySummary.build(
            hr = awake + asleep,
            spo2 = emptyList(),
            steps = emptyList(),
            sleepSessions = listOf(night(2026, 8, 4, 23, 7)),
            spo2Times = emptySet(),
            zone = zone,
        )
        val day = days.first { it.date == LocalDate.of(2026, 8, 4) }
        assertTrue("expected a sleeping figure, got ${day.restingHr}", day.restingHr!! < 60.0)
    }

    /** A nap must never displace the actual night in a row that shows one number. */
    @Test
    fun `the longest session of a day is the one reported`() {
        val days = DailySummary.build(
            hr = emptyList(),
            spo2 = emptyList(),
            steps = emptyList(),
            sleepSessions = listOf(night(2026, 8, 4, 14, 1), night(2026, 8, 4, 23, 7)),
            spo2Times = emptySet(),
            zone = zone,
        )
        assertEquals(7 * 60, days.first { it.date == LocalDate.of(2026, 8, 4) }.sleepMinutes)
    }

    @Test
    fun `rows come back newest first and honour the limit`() {
        val steps = (1..10).map { ChartPoint(at(2026, 8, it, 12), 500.0) }
        val days = DailySummary.build(
            hr = emptyList(), spo2 = emptyList(), steps = steps,
            sleepSessions = emptyList(), spo2Times = emptySet(), zone = zone, limit = 4,
        )
        assertEquals(4, days.size)
        assertEquals(LocalDate.of(2026, 8, 10), days.first().date)
        assertEquals(days.map { it.date }.sortedDescending(), days.map { it.date })
    }

    @Test
    fun `a day with nothing in it produces no row at all`() {
        assertTrue(
            DailySummary.build(
                hr = emptyList(), spo2 = emptyList(), steps = emptyList(),
                sleepSessions = emptyList(), spo2Times = emptySet(), zone = zone,
            ).isEmpty(),
        )
    }

    /** The index is per-day, computed from that day's own numbers, and present when scoreable. */
    @Test
    fun `a full day scores an index`() {
        val start = at(2026, 8, 4, 23)
        val asleep = (0 until 400).map { ChartPoint(start + it * 60_000L, 54.0 + it % 4) }
        val days = DailySummary.build(
            hr = asleep,
            spo2 = (0 until 20).map { ChartPoint(at(2026, 8, 4, 8) + it * 600_000L, 96.0) },
            steps = listOf(ChartPoint(at(2026, 8, 4, 12), 6000.0)),
            sleepSessions = listOf(night(2026, 8, 4, 23, 7)),
            spo2Times = emptySet(),
            zone = zone,
        )
        val day = days.first { it.date == LocalDate.of(2026, 8, 4) }
        assertTrue("expected a score, got ${day.index}", day.index?.value != null)
    }
}
