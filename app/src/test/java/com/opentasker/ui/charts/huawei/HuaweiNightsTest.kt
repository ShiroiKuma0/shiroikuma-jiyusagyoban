package com.opentasker.ui.charts.huawei

import com.opentasker.core.storage.HuaweiSleepEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sessions → nights — [HuaweiNights.nights], on the shapes found in 白い熊's own record. */
class HuaweiNightsTest {

    private val h = 3600L

    /** A session of one staged segment filling its whole span. */
    private fun part(from: Long, to: Long, stage: Int = 1) = HuaweiNights.Part(
        from, to, listOf(HuaweiSleepEntity(from, (to - from).toInt(), stage, from, to, 1)),
    )

    private fun asleepHours(night: List<HuaweiNights.Part>) =
        HuaweiNights.segmentsOf(night).filter { it.third != 4 }.sumOf { it.second } / 3600.0

    @Test
    fun `a mid-night snapshot under the same bed time is dropped, not added`() {
        // 2026-11-06/07: 20:36–23:53 stored at 23:53, then 20:36–07:10 in the morning.
        val nights = HuaweiNights.nights(listOf(part(0, 3 * h + 17 * 60), part(0, 10 * h + 34 * 60)))
        assertEquals(1, nights.size)
        assertEquals(10 * h + 34 * 60, nights.single().single().endSeconds)
    }

    @Test
    fun `two sleeps an hour apart are one night, the hour up shown as awake`() {
        // 2026-10-09/10: 22:04–05:40, up 68 minutes, 06:48–10:06.
        val first = part(0, 7 * h + 36 * 60)
        val second = part(8 * h + 44 * 60, 12 * h + 2 * 60)
        val night = HuaweiNights.nights(listOf(first, second)).single()
        assertEquals(2, night.size)
        assertEquals(7.6 + 3.3, asleepHours(night), 0.01)
        val awake = HuaweiNights.segmentsOf(night).single { it.third == 4 }
        assertEquals(68 * 60, awake.second)
    }

    @Test
    fun `an evening nap three hours before the night stays apart`() {
        val nights = HuaweiNights.nights(listOf(part(17 * h, 18 * h), part(21 * h, 29 * h)), utc)
        assertEquals(2, nights.size)
    }

    private val utc = java.time.ZoneOffset.UTC

    @Test
    fun `back to bed in the morning is the rest of the night, even filed as a nap`() {
        // 2026-11-07/08: 20:54–04:01, up until 06:27, then one stage-5 block to 08:40.
        val night = HuaweiNights.nights(
            listOf(part(20 * h + 54 * 60, 28 * h + 60), part(30 * h + 27 * 60, 32 * h + 40 * 60, stage = 5)),
            utc,
        ).single()
        assertEquals(2, night.size)
        assertEquals(7.12 + 2.22, asleepHours(night), 0.01)
    }

    @Test
    fun `an afternoon nap is never joined to the night`() {
        // A night ending 08:30, then a stage-5 sleep from 10:15 — after 10:00, so a nap.
        val nights = HuaweiNights.nights(listOf(part(0, 8 * h + 30 * 60), part(10 * h + 15 * 60, 11 * h, stage = 5)), utc)
        assertEquals(2, nights.size)
    }
}
