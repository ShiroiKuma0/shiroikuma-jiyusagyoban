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
}
