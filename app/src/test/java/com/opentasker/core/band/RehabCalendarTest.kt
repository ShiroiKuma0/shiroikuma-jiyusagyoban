package com.opentasker.core.band

import com.opentasker.ui.charts.huawei.rehabCutoutStart
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The 機能訓練 calendar's arithmetic — the two-week cut-out, and the key a day is filed under.
 *
 * Both are the kind of thing that looks obviously right and is wrong on one weekday in seven, which
 * is exactly how long it takes to notice.
 */
class RehabCalendarTest {

    /**
     * The cut-out is always TWO FULL ROWS, whatever day it is.
     *
     * The card shows two weeks under the morning rating, and a card whose height changes with the
     * weekday would move everything below it every Monday. Anchoring on "the last 14 days" does that:
     * on a Thursday it starts mid-week, the grid pads to the preceding Monday, and the cut-out comes
     * out three rows tall. Anchoring on the Monday of last week cannot.
     */
    @Test
    fun `the cut-out starts on a Monday, every day of the week`() {
        val start = LocalDate.of(2026, 9, 7)   // a Monday
        for (offset in 0..13) {
            val today = start.plusDays(offset.toLong())
            val from = rehabCutoutStart(today)
            assertEquals(
                "$today: the cut-out must open on a Monday",
                DayOfWeek.MONDAY,
                from.dayOfWeek,
            )
            val days = java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1
            assertEquals(
                "$today: eight to fourteen days, which is two rows once padded",
                true,
                days in 8..14,
            )
        }
    }

    /** `yyyyMMdd`, the same shape every other day-keyed store in this app uses. */
    @Test
    fun `a day is keyed as yyyyMMdd`() {
        assertEquals(20_260_903L, RehabLog.dateKeyOf(LocalDate.of(2026, 9, 3)))
        assertEquals(20_251_207L, RehabLog.dateKeyOf(LocalDate.of(2025, 12, 7)))
        // The single-digit month and day pad, or the key would sort wrongly and collide with itself.
        assertEquals(20_260_101L, RehabLog.dateKeyOf(LocalDate.of(2026, 1, 1)))
    }
}
