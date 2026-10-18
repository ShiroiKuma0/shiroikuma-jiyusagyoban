package com.opentasker.ui.charts

import com.opentasker.ui.charts.huawei.HuaweiPwaCheck
import com.opentasker.ui.charts.huawei.pwaWeek
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The arrhythmia card counts checks per local day the way the watch's own "Last 7 days" screen does:
 * on 2026-10-18 it read 3 on the 17th and 34 on the 18th. The first 36 of those, as synced at 16:49,
 * must land 3 / 33 in Prague time — the first three are 22:36, 22:50 and 23:40 on the 17th.
 */
class HuaweiPwaWeekTest {
    private val prague = ZoneId.of("Europe/Prague")
    private val times = listOf(
        1792269403L, 1792270237L, 1792273236L, 1792275636L, 1792276238L, 1792277436L, 1792278635L,
        1792279236L, 1792279835L, 1792280434L, 1792281035L, 1792283435L, 1792284036L, 1792284634L,
        1792285235L, 1792285834L, 1792286435L, 1792287035L, 1792287634L, 1792288235L, 1792288834L,
        1792290634L, 1792291846L, 1792292437L, 1792293035L, 1792293635L, 1792294238L, 1792295435L,
        1792296035L, 1792296635L, 1792297236L, 1792297835L, 1792299035L, 1792302638L, 1792309236L,
        1792315837L,
    )

    @Test
    fun `seven Prague days ending today, counted like the watch`() {
        val checks = times.map { HuaweiPwaCheck(it * 1000, 0) } + HuaweiPwaCheck(1792281635_000L, 2)
        val week = pwaWeek(checks, prague, LocalDate.of(2026, 10, 18))
        assertEquals((12..18).toList(), week.map { it.first.dayOfMonth })
        assertEquals(listOf(0, 0, 0, 0, 0, 3, 34), week.map { it.second })
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1), week.map { it.third })
    }
}
