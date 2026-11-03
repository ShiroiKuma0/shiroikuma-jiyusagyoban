package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The night table's rows — [RecoverySource.tableNights].
 *
 * 2026-11-03: 白い熊 slept 04:04–07:12, the sync stored all of it, and the morning's row came up
 * empty, because the table took its nights from [RecoverySource.nights], whose four-hour floor exists
 * for the nocturnal-HR window and drops a short night "entirely".
 */
class TableNightsTest {

    private val hour = 3_600_000L
    // UTC throughout, so minute-of-day and the morning are plain arithmetic.
    private val minuteOfDay = { ms: Long -> ((ms % 86_400_000L) / 60_000L).toDouble() }
    private val morningOf = { ms: Long -> ms / 86_400_000L }

    private fun session(fromHour: Double, toHour: Double, day: Long = 10): SleepSession {
        val start = day * 86_400_000L + (fromHour * hour).toLong()
        val end = day * 86_400_000L + (toHour * hour).toLong()
        return SleepSession(start, end, listOf(SleepRun(start, end, '2')))
    }

    @Test
    fun `a three-hour night is a row, though not a full night`() {
        val short = session(4.07, 7.2)
        assertEquals(listOf(short), RecoverySource.tableNights(listOf(short), minuteOfDay, morningOf))
        assertTrue(RecoverySource.nights(listOf(short), minuteOfDay).isEmpty())
    }

    @Test
    fun `an afternoon nap is still not a night`() {
        val nap = session(14.0, 16.5)
        assertTrue(RecoverySource.tableNights(listOf(nap), minuteOfDay, morningOf).isEmpty())
    }

    @Test
    fun `a doze after the night never displaces the night`() {
        val night = session(0.5, 6.0)
        val doze = session(7.0, 8.0)
        assertEquals(listOf(night), RecoverySource.tableNights(listOf(night, doze), minuteOfDay, morningOf))
    }
}

/** 白い熊's own bedtime over the watch's — [SleepShape.withNightStarts]. */
class NightStartsTest {

    private val hour = 3_600_000L
    private val morningOf = { ms: Long -> ms / 86_400_000L }
    private val day = 10L * 86_400_000L

    // The 2026-11-03 case: the watch began at 04:04 and ended at 07:12; 白い熊 fell asleep at 01:30.
    private val watch = SleepSession(
        day + (4.07 * hour).toLong(), day + (7.2 * hour).toLong(),
        listOf(
            SleepRun(day + (4.07 * hour).toLong(), day + 5 * hour, '1'),
            SleepRun(day + 5 * hour, day + (7.2 * hour).toLong(), '2'),
        ),
    )

    @Test
    fun `an earlier bedtime adds the gap as unstaged sleep`() {
        val at = day + (1.5 * hour).toLong()
        val fixed = SleepShape.withNightStarts(listOf(watch), mapOf(10L to at), morningOf).single()
        assertEquals(at, fixed.startMs)
        assertEquals(((watch.startMs - at) / 60_000L).toInt(), fixed.unstaged)
        assertEquals(watch.deep, fixed.deep)
        // The share ignores the unstaged stretch: the watch's own staging decides it, unchanged.
        assertEquals(watch.deepRemShare!!, fixed.deepRemShare!!, 1e-9)
        assertTrue(RecoverySource.sleepMinutes(fixed) > RecoverySource.sleepMinutes(watch))
    }

    @Test
    fun `a later bedtime trims the front`() {
        val at = day + (4.5 * hour).toLong()
        val fixed = SleepShape.withNightStarts(listOf(watch), mapOf(10L to at), morningOf).single()
        assertEquals(at, fixed.startMs)
        assertEquals(at, fixed.runs.first().startMs)
        assertEquals(0, fixed.unstaged)
    }

    @Test
    fun `a bedtime for another morning or out of range changes nothing`() {
        assertEquals(listOf(watch), SleepShape.withNightStarts(listOf(watch), mapOf(11L to day), morningOf))
        val dayBefore = day - 20 * hour
        assertEquals(listOf(watch), SleepShape.withNightStarts(listOf(watch), mapOf(10L to dayBefore), morningOf))
    }
}
