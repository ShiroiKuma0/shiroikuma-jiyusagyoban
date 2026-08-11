package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time re-keying of ratings written before the night-keying fix.
 *
 * The fix changed what a `yyyyMMdd` key means — from the morning the answer was typed on to the start
 * date of the night it describes — and left the existing entries where they were, so each read a
 * night late. These pin the shift itself: it is calendar arithmetic, not subtraction on the integer,
 * which is where an off-by-one at a month boundary would hide.
 */
class RecoveryLogMigrationTest {

    @Test
    fun `every rating moves to the night it describes`() {
        val shifted = RecoveryLog.shiftedToNightKeys(mapOf(20260810L to 2, 20260809L to 3))
        assertEquals(mapOf(20260809L to 2, 20260808L to 3), shifted)
    }

    @Test
    fun `a month boundary is a calendar step, not a decrement`() {
        // 20260801 - 1 is 20260800, which is not a day. Only real date arithmetic gets July.
        assertEquals(mapOf(20260731L to 4), RecoveryLog.shiftedToNightKeys(mapOf(20260801L to 4)))
    }

    @Test
    fun `a year boundary crosses into the previous December`() {
        assertEquals(mapOf(20251231L to 1), RecoveryLog.shiftedToNightKeys(mapOf(20260101L to 1)))
    }

    @Test
    fun `a leap year keeps its 29th`() {
        assertEquals(mapOf(20240229L to 5), RecoveryLog.shiftedToNightKeys(mapOf(20240301L to 5)))
        // 2026 is not a leap year, so the same March 1st lands on the 28th.
        assertEquals(mapOf(20260228L to 5), RecoveryLog.shiftedToNightKeys(mapOf(20260301L to 5)))
    }

    @Test
    fun `a run of consecutive days stays a run, one day earlier`() {
        val before = (6..10).associate { (20260800L + it) to it - 5 }
        val after = RecoveryLog.shiftedToNightKeys(before)
        assertEquals(before.size, after.size)
        assertEquals((5..9).associate { (20260800L + it) to it - 4 }, after)
    }

    @Test
    fun `an impossible date is dropped rather than guessed at`() {
        val shifted = RecoveryLog.shiftedToNightKeys(mapOf(20260231L to 3, 20260810L to 2))
        assertEquals(mapOf(20260809L to 2), shifted)
    }

    @Test
    fun `no two ratings can collide on one night`() {
        val before = (1..40).associate { (20260700L + it) to (it % 5) + 1 }
            .filterKeys { RecoveryLog.shiftedToNightKeys(mapOf(it to 1)).isNotEmpty() }
        val after = RecoveryLog.shiftedToNightKeys(before)
        assertTrue("the shift must not lose a rating", after.size == before.size)
    }
}
