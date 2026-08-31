package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The day-by-day tables printed under each full-screen chart.
 *
 * Two things worth pinning. A **night is a session, not a summary**: the day table keeps only the
 * longest session of a day because one row can show one number, but this is a history and dropping a
 * recorded nap would be the screen pretending it did not happen. And **steps are a total** where
 * everything else is a median — a day of per-minute counts summed is a real figure, a day of heart
 * rate summed is nothing at all.
 */
class MetricHistoryTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a day carries its low, high, median, total and count`() {
        val points = listOf(60.0, 80.0, 70.0, 90.0, 65.0).mapIndexed { i, v ->
            ChartPoint(at(2026, 8, 7, 8 + i), v)
        }
        val day = MetricHistory.days(points, zone).single()
        assertEquals(LocalDate.of(2026, 8, 7), day.date)
        assertEquals(60.0, day.lo, 0.0)
        assertEquals(90.0, day.hi, 0.0)
        assertEquals(70.0, day.median, 0.0)
        assertEquals(365.0, day.total, 0.0)
        assertEquals(5, day.samples)
    }

    @Test
    fun `days come back newest first and split on the calendar day`() {
        val points = (5..8).map { d -> ChartPoint(at(2026, 8, d, 12), d.toDouble()) }
        val days = MetricHistory.days(points, zone)
        assertEquals(listOf(8, 7, 6, 5).map { LocalDate.of(2026, 8, it) }, days.map { it.date })
    }

    /** A reading just before midnight belongs to that day, not the next one. */
    @Test
    fun `the day boundary is local midnight`() {
        val points = listOf(
            ChartPoint(at(2026, 8, 6, 23, 59), 1.0),
            ChartPoint(at(2026, 8, 7, 0, 1), 2.0),
        )
        val days = MetricHistory.days(points, zone)
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2026, 8, 7), days.first().date)
    }

    @Test
    fun `the limit keeps the newest days`() {
        val points = (1..30).map { d -> ChartPoint(at(2026, 8, d, 12), 1.0) }
        val days = MetricHistory.days(points, zone, limit = 3)
        assertEquals(3, days.size)
        assertEquals(LocalDate.of(2026, 8, 30), days.first().date)
    }

    @Test
    fun `an empty series has no rows`() {
        assertTrue(MetricHistory.days(emptyList(), zone).isEmpty())
    }

    // --- nights ---------------------------------------------------------------------------------

    private fun session(day: Int, hour: Int, hours: Int): SleepSession {
        val start = at(2026, 8, day, hour)
        val runs = (0 until hours * 2).map { i ->
            SleepRun(
                start + i * 30 * 60_000L,
                start + (i + 1) * 30 * 60_000L,
                when (i % 4) { 0 -> '1'; 1 -> '2'; 2 -> '3'; else -> '5' },
            )
        }
        return SleepSession(start, start + hours * 3_600_000L, runs)
    }

    @Test
    fun `a night keeps its extent as well as its duration`() {
        val night = MetricHistory.nights(listOf(session(6, 23, 8)), zone).single()
        assertEquals(LocalDate.of(2026, 8, 6), night.date)
        assertEquals(at(2026, 8, 6, 23), night.startMs)
        assertEquals(at(2026, 8, 7, 7), night.endMs)
        assertEquals(8 * 60, night.totalMinutes)
        assertEquals(night.totalMinutes, night.deep + night.light + night.rem + night.awake)
    }

    /** The difference from [DailySummary]: a history shows every session, not the day's longest. */
    @Test
    fun `two sessions on one day both get a row`() {
        val nights = MetricHistory.nights(listOf(session(6, 14, 1), session(6, 23, 8)), zone)
        assertEquals(2, nights.size)
        assertEquals(listOf(8 * 60, 60), nights.map { it.totalMinutes })
    }

    @Test
    fun `nights come back newest first`() {
        val nights = MetricHistory.nights(
            listOf(session(4, 23, 7), session(6, 23, 8), session(5, 23, 6)),
            zone,
        )
        assertEquals(listOf(6, 5, 4).map { LocalDate.of(2026, 8, it) }, nights.map { it.date })
    }

    @Test
    fun `stage percentages sum to about a hundred`() {
        val night = MetricHistory.nights(listOf(session(6, 23, 8)), zone).single()
        val total = night.pctOf(night.deep) + night.pctOf(night.light) +
            night.pctOf(night.rem) + night.pctOf(night.awake)
        assertTrue("percentages summed to $total", total in 98..102)
    }

    @Test
    fun `a night with no runs reports zero rather than dividing by zero`() {
        val empty = SleepSession(at(2026, 8, 6, 23), at(2026, 8, 7, 7), emptyList())
        val night = MetricHistory.nights(listOf(empty), zone).single()
        assertEquals(0, night.totalMinutes)
        assertEquals(0, night.pctOf(0))
    }
}
