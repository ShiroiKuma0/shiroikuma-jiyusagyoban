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

    // --- the 2026-08-12 flip: 1 = Wrecked … 5 = Great became 1 = Great … 5 = Wrecked ---------------

    /**
     * The store holds bare integers, so a rating written under the old scheme does not merely display
     * differently after the flip — it means its own opposite, and goes on feeding the baseline and the
     * adverse count that way.
     */
    @Test
    fun `every rating is re-numbered onto the best-first scale`() {
        assertEquals(
            mapOf(20260811L to 4, 20260810L to 5, 20260809L to 4, 20260808L to 4),
            RecoveryLog.flippedToBestFirst(
                // 白い熊's four ratings as they stood: two "Below par", one "Wrecked", one more.
                mapOf(20260811L to 2, 20260810L to 1, 20260809L to 2, 20260808L to 2),
            ),
        )
    }

    @Test
    fun `the middle step is its own opposite`() {
        assertEquals(mapOf(20260810L to 3), RecoveryLog.flippedToBestFirst(mapOf(20260810L to 3)))
    }

    /** An involution: applying it twice is what the run-once flag exists to prevent. */
    @Test
    fun `flipping twice is the identity, which is why it must run once`() {
        val before = (1..5).associate { (20260800L + it) to it }
        assertEquals(before, RecoveryLog.flippedToBestFirst(RecoveryLog.flippedToBestFirst(before)))
    }

    @Test
    fun `the flip keeps every key and every rating in range`() {
        val before = (1..40).associate { (20260700L + it) to (it % 5) + 1 }
        val after = RecoveryLog.flippedToBestFirst(before)
        assertEquals(before.keys, after.keys)
        assertTrue("nothing may leave 1..5", after.values.all { it in 1..5 })
    }
}
