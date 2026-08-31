package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one-time re-keying [RecoveryLog] has been through, pinned.
 *
 * A `yyyyMMdd` key has meant three different things — the morning the answer was typed (until
 * 2026-08-10), the date the night STARTED (until 2026-08-16), and the morning the night ENDED — and
 * the store cannot tell them apart by inspection. So each migration is a one-way move over data
 * 白い熊 authored, run once, with nothing to check it against afterwards. These are that check.
 *
 * They are also where an off-by-one at a month or year boundary would hide, which is why the first
 * two are calendar arithmetic rather than subtraction on the integer, and why the third refuses to
 * do arithmetic at all: it asks the recorded nights.
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

    // ---- 2026-08-16: start dates become the mornings they ended on ------------------------------
    //
    // Driven by the recorded nights rather than by `+1 day`, because `+1` is right for a bedtime
    // before midnight and wrong for one after it, and 白い熊's own store held both.

    /** 白い熊's real store the morning the rule changed, and the nights it was migrated against. */
    private val realRatings = mapOf(
        20260808L to 4, 20260809L to 4, 20260810L to 5, 20260811L to 4,
        20260812L to 3, 20260813L to 4, 20260814L to 4, 20260815L to 2,
    )
    private val realNights = mapOf(
        20260807L to 20260808L, 20260808L to 20260809L, 20260809L to 20260810L,
        20260810L to 20260811L, 20260811L to 20260812L, 20260812L to 20260813L,
        20260813L to 20260814L,
        // The one night that began after midnight: it ends on the day it started.
        20260815L to 20260815L,
    )

    @Test
    fun `every rating lands on the morning its night ended`() {
        val move = RecoveryLog.movedToMorningKeys(realRatings, realNights)!!
        assertEquals(
            mapOf(
                20260809L to 4, 20260810L to 4, 20260811L to 5, 20260812L to 4,
                20260813L to 3, 20260814L to 4, 20260815L to 2,
            ),
            move.moved,
        )
        assertTrue("nothing should be left unplaced", move.unresolved.isEmpty())
    }

    /** The post-midnight night is the one `+1 day` would have broken. It must not move. */
    @Test
    fun `a night begun after midnight keeps its date`() {
        val move = RecoveryLog.movedToMorningKeys(mapOf(20260815L to 2), mapOf(20260815L to 20260815L))!!
        assertEquals(mapOf(20260815L to 2), move!!.moved)
    }

    @Test
    fun `the retired key is dropped rather than moved`() {
        assertEquals(20260814L, RecoveryLog.RETIRED_KEY)
        val move = RecoveryLog.movedToMorningKeys(realRatings, realNights)!!
        // 20260814 survives as a KEY — the 08-13 night moved onto it — but carrying the 08-13
        // rating, not the retired one. Both were 4, so assert on the count instead.
        assertEquals(realRatings.size - 1, move.moved.size)
    }

    @Test
    fun `two ratings wanting one morning is refused, not resolved`() {
        // Exactly the collision 白い熊 settled by hand: a hand-filed rating on the 14th, and the
        // 13th's night ending on the 14th. Without the retirement this must refuse rather than
        // silently keep whichever the map iterated last.
        val ratings = mapOf(20260813L to 4, 20260899L to 5)
        val nights = mapOf(20260813L to 20260814L, 20260899L to 20260814L)
        assertNull(RecoveryLog.movedToMorningKeys(ratings, nights))
    }

    @Test
    fun `a rating whose night is not on record keeps its key and is reported`() {
        val move = RecoveryLog.movedToMorningKeys(
            mapOf(20260801L to 3, 20260812L to 4),
            mapOf(20260812L to 20260813L),
        )!!
        assertEquals(mapOf(20260801L to 3, 20260813L to 4), move.moved)
        assertEquals(setOf(20260801L), move.unresolved)
    }

    @Test
    fun `an unplaceable key colliding with a placed one is refused`() {
        // The stranded rating stays on 20260813; the 12th's night also ends there. Silently keeping
        // one would destroy an answer 白い熊 typed.
        assertNull(
            RecoveryLog.movedToMorningKeys(
                mapOf(20260813L to 3, 20260812L to 4),
                mapOf(20260812L to 20260813L),
            ),
        )
    }

    @Test
    fun `no rating is lost or invented`() {
        val move = RecoveryLog.movedToMorningKeys(realRatings, realNights)!!
        assertEquals(
            (realRatings - RecoveryLog.RETIRED_KEY).values.sorted(),
            move.moved.values.sorted(),
        )
    }
}
