package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The month headings, and the window the calendar draws them across.
 *
 * The Japanese rendering is the point of this file. 白い熊 asked for the imperial year in kanji
 * numerals and nothing else (2026-08-18) — `令和八年 八月`, not `2026年 8月` and not `令和8年` —
 * matching what the sister calendar fork already prints. That is a specific string, produced by two
 * pieces that can each drift on their own (the platform's era table, and our numerals), so it is
 * pinned here rather than checked by eye on a preview.
 */
class BandMonthsTest {

    private fun ym(y: Int, m: Int) = YearMonth.of(y, m)

    // --- kanji numerals ---------------------------------------------------------------------

    @Test
    fun `single digits are the bare numeral`() {
        assertEquals("一", BandMonths.kanjiNumber(1))
        assertEquals("八", BandMonths.kanjiNumber(8))
        assertEquals("九", BandMonths.kanjiNumber(9))
    }

    @Test
    fun `the teens lead with a bare 十, never 一十`() {
        assertEquals("十", BandMonths.kanjiNumber(10))
        assertEquals("十一", BandMonths.kanjiNumber(11))
        assertEquals("十二", BandMonths.kanjiNumber(12))
        assertEquals("十九", BandMonths.kanjiNumber(19))
    }

    @Test
    fun `round tens drop the unit`() {
        assertEquals("二十", BandMonths.kanjiNumber(20))
        assertEquals("三十一", BandMonths.kanjiNumber(31))
        assertEquals("九十九", BandMonths.kanjiNumber(99))
    }

    // --- the year ---------------------------------------------------------------------------

    @Test
    fun `Japanese prints the imperial year in kanji`() {
        assertEquals("令和八年", BandMonths.yearLabel(ym(2026, 8), BandLanguage.JA))
        assertEquals("令和七年", BandMonths.yearLabel(ym(2025, 12), BandLanguage.JA))
    }

    /** The first year of an era is 元年 by convention, and 一年 by nobody. */
    @Test
    fun `the first year of an era is 元年`() {
        assertEquals("令和元年", BandMonths.yearLabel(ym(2019, 6), BandLanguage.JA))
    }

    /**
     * January 2019 is still 平成 — the era changed on 1 May. A hardcoded "令和 = year − 2018" would
     * pass every other case in this file and fail this one, which is why it is here.
     */
    @Test
    fun `an era boundary is taken from the calendar, not from arithmetic`() {
        assertEquals("平成三十一年", BandMonths.yearLabel(ym(2019, 1), BandLanguage.JA))
        assertEquals("令和元年", BandMonths.yearLabel(ym(2019, 5), BandLanguage.JA))
    }

    @Test
    fun `English keeps the Gregorian year`() {
        assertEquals("2026", BandMonths.yearLabel(ym(2026, 8), BandLanguage.EN))
    }

    // --- the month --------------------------------------------------------------------------

    @Test
    fun `Japanese months are kanji numerals`() {
        assertEquals("一月", BandMonths.monthLabel(ym(2026, 1), BandLanguage.JA))
        assertEquals("八月", BandMonths.monthLabel(ym(2026, 8), BandLanguage.JA))
        assertEquals("十月", BandMonths.monthLabel(ym(2026, 10), BandLanguage.JA))
        assertEquals("十二月", BandMonths.monthLabel(ym(2026, 12), BandLanguage.JA))
    }

    @Test
    fun `English months are named`() {
        assertEquals("August", BandMonths.monthLabel(ym(2026, 8), BandLanguage.EN))
    }

    // --- keys -------------------------------------------------------------------------------

    @Test
    fun `a yyyyMMdd key resolves to its month`() {
        assertEquals(ym(2026, 8), BandMonths.ofDateKey(20_260_818L))
        assertEquals(ym(2026, 1), BandMonths.ofDateKey(20_260_101L))
    }

    @Test
    fun `a key that is not a date resolves to nothing rather than throwing`() {
        assertNull(BandMonths.ofDateKey(0L))
        assertNull(BandMonths.ofDateKey(20_261_318L)) // month 13
    }

    @Test
    fun `an epoch day resolves to its month`() {
        assertEquals(ym(2026, 8), BandMonths.ofEpochDay(LocalDate.of(2026, 8, 18).toEpochDay()))
    }

    // --- the calendar window ------------------------------------------------------------------

    /**
     * A month and a half, not five weeks, and always starting on a Monday so the grid's first row is
     * a whole week. Monday-alignment is what makes the span vary at all — 45 days back, rounded down
     * to the Monday on or before it — which is why the bound is a range and not a number.
     */
    @Test
    fun `the grid window always starts on a Monday, 45 to 51 days back`() {
        for (offset in 0..13) {
            val today = LocalDate.of(2026, 8, 18).plusDays(offset.toLong())
            val start = LocalDate.ofEpochDay(RecoveryBuild.gridStart(today.toEpochDay()))
            assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
            val span = today.toEpochDay() - start.toEpochDay() + 1
            assertTrue("span was $span for $today", span in 45..51)
        }
    }

    /** The window 白い熊's own screenshots were taken in, spelled out. */
    @Test
    fun `on 2026-08-18 the calendar reaches back to 29 June`() {
        val start = RecoveryBuild.gridStart(LocalDate.of(2026, 8, 18).toEpochDay())
        assertEquals(LocalDate.of(2026, 6, 29), LocalDate.ofEpochDay(start))
    }
}
