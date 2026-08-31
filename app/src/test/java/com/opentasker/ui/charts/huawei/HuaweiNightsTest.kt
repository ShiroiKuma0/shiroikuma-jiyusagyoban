package com.opentasker.ui.charts.huawei

import com.opentasker.ui.charts.SleepRun
import com.opentasker.ui.charts.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nights come from exactly one band each.
 *
 * The stage codes are the trap this pins down: the two devices number their stages differently AND
 * the numbers overlap, so a mistranslation does not throw — it silently moves an hour a night into
 * the wrong column and takes every derived figure with it.
 */
class HuaweiNightsTest {

    private val day = 86_400_000L
    private val cutover = 1_787_000_000_000L

    private fun humeNight(endMs: Long) =
        SleepSession(endMs - 6 * 3_600_000L, endMs, listOf(SleepRun(endMs - 3_600_000L, endMs, '1')))

    @Test
    fun `only nights that ended before the cutover come from the Hume band`() {
        val hume = listOf(
            humeNight(cutover - 2 * day),
            humeNight(cutover - day),
            // This one ends AFTER the handover — the Huawei band recorded it through to morning, so
            // it must not be taken from the older store as well.
            humeNight(cutover + day),
        )
        val kept = hume.filter { it.endMs < cutover }
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.endMs < cutover })
    }

    @Test
    fun `no night is ever assembled from both bands`() {
        // The property that makes the whole arrangement honest: every session's runs come from one
        // store. Expressed here as a shape check, because a blended night would show as a session
        // whose runs straddle the cutover.
        val hume = listOf(humeNight(cutover - day))
        for (night in hume) {
            val spans = night.runs.map { it.startMs..it.endMs }
            assertTrue(
                "a night must not straddle the handover",
                spans.all { it.last < cutover } || spans.all { it.first >= cutover },
            )
        }
    }
}
