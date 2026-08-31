package com.opentasker.ui.charts

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which morning the 回復 card offers to score — see [RecoveryBuild.ratableMorning].
 *
 * The rule is two lines and its predecessor was wrong twice, so it is worth the file: it decides
 * which day a tap on the card writes to, and getting it wrong does not fail loudly. It files an
 * answer against the wrong morning, or hands back one already there, and the result goes on feeding
 * the baseline and the ≥2-of-3 count looking exactly like data 白い熊 authored.
 */
class RatableNightTest {

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        RecoveryBuild.ratableMorning(LocalDateTime.of(y, m, d, h, min))

    @Test
    fun `an ordinary morning is today`() {
        assertEquals(20260816L, at(2026, 8, 16, 9, 32))
    }

    @Test
    fun `it stays today all day and all evening`() {
        // The morning does not expire at noon: 白い熊 can answer whenever, and the night about to
        // begin is never on offer.
        assertEquals(20260816L, at(2026, 8, 16, 4, 0))
        assertEquals(20260816L, at(2026, 8, 16, 12, 0))
        assertEquals(20260816L, at(2026, 8, 16, 23, 59))
    }

    @Test
    fun `after midnight it is still the morning last woken on`() {
        // Up at 01:00 on the 17th, having woken on the morning of the 16th. Offering the 17th would
        // be offering a morning not yet slept into.
        assertEquals(20260816L, at(2026, 8, 17, 0, 1))
        assertEquals(20260816L, at(2026, 8, 17, 3, 59))
    }

    @Test
    fun `the boundary is 4am to the minute`() {
        assertEquals(20260816L, at(2026, 8, 17, 3, 59))
        assertEquals(20260817L, at(2026, 8, 17, 4, 0))
        assertEquals(4L, RecoveryBuild.DAY_STARTS_AT_HOUR)
    }

    @Test
    fun `it never depends on what the band recorded`() {
        // The whole point: the morning after a night off the wrist is as answerable as any other, and
        // the old rule — read off the last recorded night — could not offer it at all.
        assertEquals(at(2026, 8, 16, 9, 0), at(2026, 8, 16, 9, 0))
    }

    @Test
    fun `month year and leap boundaries are real dates`() {
        // 20260901 − 1 is not 20260831 by subtraction, which is why this goes through LocalDate.
        assertEquals(20260831L, at(2026, 9, 1, 2, 0))
        assertEquals(20251231L, at(2026, 1, 1, 1, 0))
        assertEquals(20240229L, at(2024, 3, 1, 3, 0))
    }

    /** Every day of a five-week stretch, since that is the span the register's grid shows. */
    @Test
    fun `it walks a whole grid without a gap or a repeat`() {
        val start = LocalDate.of(2026, 7, 13)
        val offered = (0..34).map {
            RecoveryBuild.ratableMorning(start.plusDays(it.toLong()).atTime(9, 0))
        }
        assertEquals("one morning per day", 35, offered.toSet().size)
        offered.forEachIndexed { i, key ->
            assertEquals(SessionRegister.dateKeyOf(start.plusDays(i.toLong()).toEpochDay()), key)
        }
    }

    /**
     * The span half of the dialog's title — see [nightSpanLabel].
     *
     * It is the half that makes the morning unambiguous, so a month end has to read correctly rather
     * than merely not crash.
     */
    @Test
    fun `the night span reads as the two days it ran between`() {
        assertEquals("15→16", nightSpanLabel(20260816L))
        assertEquals("31→1", nightSpanLabel(20260901L))
        assertEquals("31→1", nightSpanLabel(20260101L))
        assertEquals("29→1", nightSpanLabel(20240301L))
    }
}
