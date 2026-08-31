package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep file is append-only, and reading only its first block pinned the app to the oldest
 * night it had ever seen.
 *
 * The fixture is 白い熊's own file at the moment the bug was found: 1525 bytes holding two nights,
 * where the previous day's copy of the same file had been 643 — the second night begins exactly
 * where the old file ended. The card had been showing the 21st for two days, every sync reporting
 * "18 sleep segments", with nothing to say the night was stale.
 */
class HuaweiSleepMultiNightTest {

    private fun bytes() = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("huawei/sleep-two-nights.bin"),
    ) { "fixture missing" }.readBytes()

    @Test
    fun `both nights are parsed, oldest first`() {
        val nights = HuaweiSleep.parseAll(bytes())
        assertEquals(2, nights.size)
        assertTrue("oldest first", nights[0].startSeconds < nights[1].startSeconds)
    }

    @Test
    fun `the second night is the one the old parser threw away`() {
        val nights = HuaweiSleep.parseAll(bytes())
        // 2026-08-22 20:41 .. 2026-08-23 08:10 local — the night that never reached the screen.
        assertEquals(1_787_424_060L, nights[1].startSeconds)
        assertEquals(1_787_465_400L, nights[1].endSeconds)
        assertTrue("segments must be present", nights[1].segments.isNotEmpty())
    }

    @Test
    fun `segments never run past their session, in either night`() {
        for (night in HuaweiSleep.parseAll(bytes())) {
            val last = night.segments.last()
            val end = last.startSeconds + last.durationSeconds
            assertTrue(
                "night at ${night.startSeconds} overruns: $end > ${night.endSeconds}",
                end <= night.endSeconds + 3600,
            )
        }
    }

    @Test
    fun `parse still returns the first night, so older callers are unchanged`() {
        assertEquals(HuaweiSleep.parseAll(bytes()).first(), HuaweiSleep.parse(bytes()))
    }
}
