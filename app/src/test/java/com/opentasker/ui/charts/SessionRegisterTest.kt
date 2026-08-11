package com.opentasker.ui.charts

import com.opentasker.core.band.TrainingSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The register: pairing, and the one aggregate it is allowed to compute.
 */
class SessionRegisterTest {

    private val day = 86_400_000L
    private val base = 1_785_000_000_000L / day * day   // midnight UTC, so day maths is clean

    private fun session(dayIndex: Int, hour: Int, minutes: Int) = TrainingSessions.Session(
        startMs = base + dayIndex * day + hour * 3_600_000L,
        endMs = base + dayIndex * day + hour * 3_600_000L + minutes * 60_000L,
        label = "筋トレ",
    )

    private fun night(dayIndex: Int, hr: Double) = SessionRegister.NightReading(
        startMs = base + dayIndex * day + 23 * 3_600_000L,
        nocturnalHr = MarkerReading(RecoveryMarker.NOCTURNAL_HR, hr, 60.0, 55.0, 65.0, 0.0, RecoveryBand.USUAL, true),
        sleep = MarkerReading(RecoveryMarker.SLEEP, 480.0, 480.0, 450.0, 510.0, 0.0, RecoveryBand.USUAL, true),
        felt = MarkerReading(RecoveryMarker.FELT, 3.0, 3.0, 2.0, 4.0, 0.0, RecoveryBand.USUAL, true),
        temperature = MarkerReading(
            RecoveryMarker.TEMPERATURE, 36.4, 36.4, 36.1, 36.7, 0.0, RecoveryBand.USUAL, false,
        ),
        adverseCount = 0,
    )

    /**
     * The pairing runs FORWARD: a session is matched to the night that follows it.
     *
     * That is the direction the measured effect runs — training raises the *following* night's
     * heart rate. Matching backwards would pair every session with a night that happened before it.
     */
    @Test
    fun `a session is paired with the night that follows it`() {
        val r = SessionRegister.build(
            sessions = listOf(session(3, 17, 45)),
            nights = listOf(night(2, 60.0), night(3, 66.0), night(4, 59.0)),
            spotPoints = emptyList(),
            restingHr = 58.0,
            zoneOffsetMs = 0L,
            fromEpochDay = (base / day) ,
            toEpochDay = (base / day) + 6,
        )
        val entry = r.entries.single()
        assertNotNull(entry.night)
        assertEquals("the night of day 3, not day 2", night(3, 66.0).startMs, entry.night!!.startMs)
    }

    @Test
    fun `a session with no night after it yet is still listed`() {
        val r = SessionRegister.build(
            sessions = listOf(session(5, 17, 45)),
            nights = listOf(night(2, 60.0), night(3, 61.0)),
            spotPoints = emptyList(), restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 6,
        )
        assertEquals(1, r.entries.size)
        assertNull("nothing may be invented for a night that has not happened", r.entries.single().night)
    }

    /** A night more than the pairing window after the session is somebody else's night. */
    @Test
    fun `a night two days later is not paired`() {
        val r = SessionRegister.build(
            sessions = listOf(session(1, 9, 45)),
            nights = listOf(night(3, 62.0)),
            spotPoints = emptyList(), restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 6,
        )
        assertNull(r.entries.single().night)
    }

    @Test
    fun `the grid has one cell per day, with load and dots where they belong`() {
        val r = SessionRegister.build(
            sessions = listOf(session(2, 17, 45)),
            nights = listOf(night(2, 66.0)),
            spotPoints = listOf(
                ChartPoint(base + 2 * day + 17 * 3_600_000L + 600_000L, 100.0),
            ),
            restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 4,
        )
        assertEquals(5, r.days.size)
        val withSession = r.days.first { it.epochDay == base / day + 2 }
        assertTrue("the session's load lands on its own day", (withSession.sessionLoad ?: 0.0) > 0.0)
        assertEquals(0, withSession.adverseCount)
        assertNull(r.days.first { it.epochDay == base / day }.sessionLoad)
    }

    /**
     * The contrast is the only aggregate, and it stays silent until both sides are real.
     *
     * Four nights either side is already generous for a single person; below that it is two numbers
     * pretending to be a comparison.
     */
    @Test
    fun `the contrast waits for enough nights on both sides`() {
        val sessions = (0 until 2).map { session(it * 2, 17, 45) }
        val nights = (0 until 8).map { night(it, 60.0) }
        val thin = SessionRegister.build(
            sessions, nights, emptyList(), 58.0, 0L, base / day, base / day + 8,
        )
        assertNull("two session-nights is not a comparison", thin.contrast)

        val many = SessionRegister.build(
            (0 until 5).map { session(it * 2, 17, 45) },
            (0 until 12).map { night(it, if (it % 2 == 0) 65.0 else 59.0) },
            emptyList(), 58.0, 0L, base / day, base / day + 12,
        )
        assertNotNull(many.contrast)
        val c = many.contrast!!
        assertTrue(c.nAfterSession >= SessionRegister.MIN_CONTRAST_NIGHTS)
        assertTrue(c.nAfterRest >= SessionRegister.MIN_CONTRAST_NIGHTS)
        assertEquals("session nights were the high ones", 6.0, c.delta, 0.001)
    }

    /**
     * Each night is banded against the nights BEFORE it, never against a baseline containing itself
     * or the months that came later — the register shows what the night looked like at the time.
     */
    @Test
    fun `nights are banded against their own past only`() {
        val history = (0 until 20).map {
            RecoverySource.NightMetrics(
                startMs = base + it * day,
                nocturnalHr = if (it == 19) 75.0 else 60.0,
                sleepMinutes = 480.0,
                skinTemp = 36.4,
            )
        }
        val read = SessionRegister.readNights(history) { null }
        assertEquals(20, read.size)
        assertEquals("the first night has no past to be judged against", RecoveryBand.UNKNOWN, read.first().nocturnalHr.band)
        assertEquals("the last one is plainly high", RecoveryBand.HIGH, read.last().nocturnalHr.band)
        assertEquals(1, read.last().adverseCount)
    }

    /**
     * Every night reaches the register, newest first.
     *
     * The grid can only carry a count per day, and the per-session cards only ever covered nights
     * that followed a marked session — so with no session marked, nothing stored was printed at all.
     * The screen lists these, so this is the contract that keeps it possible.
     */
    @Test
    fun `every night is carried out of the register, newest first`() {
        val r = SessionRegister.build(
            sessions = emptyList(),
            nights = listOf(night(2, 60.0), night(4, 62.0), night(3, 61.0)),
            spotPoints = emptyList(), restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 6,
        )
        assertTrue("no session marked must not hide the nights", r.entries.isEmpty())
        assertEquals(3, r.nights.size)
        assertEquals(
            listOf(night(4, 0.0).startMs, night(3, 0.0).startMs, night(2, 0.0).startMs),
            r.nights.map { it.startMs },
        )
    }

    /** `yyyyMMdd` for a night's start instant, at the UTC offset these fixtures use. */
    private fun dateKey(ms: Long): Long = java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        .let { it.year * 10_000L + it.monthValue * 100L + it.dayOfMonth }

    /**
     * A rating filed against a date the band never recorded a night for is still a line.
     *
     * This is the hole 白い熊 fell into (2026-08-11: "where's the fourth data point?"). The table used
     * to be driven by the band's nights alone, so a rating with no night beside it was stored,
     * counted in the baseline, and displayed nowhere at all — indistinguishable from never having
     * been entered. Nothing may be invisible merely because the band has nothing to say about it.
     */
    @Test
    fun `a rating with no night of its own is still listed`() {
        val nights = listOf(night(2, 60.0), night(3, 61.0))
        val orphanDate = dateKey(night(4, 0.0).startMs)
        val r = SessionRegister.build(
            sessions = emptyList(),
            nights = nights,
            spotPoints = emptyList(), restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 6,
            ratings = mapOf(
                dateKey(night(2, 0.0).startMs) to 3,
                orphanDate to 5,
            ),
            dateOfNight = ::dateKey,
        )
        assertEquals("two nights plus the night-less rating", 3, r.rows.size)
        assertEquals("newest first", orphanDate, r.rows.first().dateKey)
        val orphan = r.rows.first()
        assertNull("there is no night to show beside it", orphan.night)
        assertEquals("but the score is not lost", 5, orphan.felt)
        assertNotNull("a rated night still carries its night", r.rows[2].night)
        assertEquals(3, r.rows[2].felt)
    }

    /** The grid carries the score itself, not only a count of markers that were off. */
    @Test
    fun `each day cell carries that night's rating`() {
        val history = (0 until 8).map {
            RecoverySource.NightMetrics(base + it * day, 60.0, 480.0, 36.4)
        }
        val read = SessionRegister.readNights(history) { 4.0 }
        val r = SessionRegister.build(
            sessions = emptyList(), nights = read,
            spotPoints = emptyList(), restingHr = 58.0, zoneOffsetMs = 0L,
            fromEpochDay = base / day, toEpochDay = base / day + 8,
        )
        val rated = r.days.filter { it.felt != null }
        assertEquals("every night with a rating shows it on the grid", read.size, rated.size)
        assertTrue("and it is the rating, not the marker count", rated.all { it.felt == 4 })
    }

    /**
     * Temperature is reported but never counted — the same rule the 回復 card follows, so the register
     * and the card can never disagree about how many markers were off on one night.
     */
    @Test
    fun `a warm night is reported without being counted`() {
        val history = (0 until 20).map {
            RecoverySource.NightMetrics(
                startMs = base + it * day,
                nocturnalHr = 60.0,
                sleepMinutes = 480.0,
                skinTemp = if (it == 19) 37.6 else 36.4,
            )
        }
        val last = SessionRegister.readNights(history) { 3.0 }.last()
        assertEquals("the warm night is banded", RecoveryBand.HIGH, last.temperature.band)
        assertEquals(37.6, last.temperature.value!!, 0.001)
        assertEquals("but it is not one of the three counted", 0, last.adverseCount)
    }
}
