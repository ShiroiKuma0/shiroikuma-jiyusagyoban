package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stitching the band's sleep segments back into nights.
 *
 * The totals below are 白い熊's real nights: the 2026-08-04 session ran 718 minutes across nine
 * segments — deep 156, light 387, REM 121, awake 54.
 */
class SleepShapeTest {

    private val m = SleepShape.MINUTE_MS

    @Test
    fun `equal stages collapse into one run, not one run per minute`() {
        val runs = SleepShape.runs(SleepSegmentInput(0, 6, "222111"))
        assertEquals(2, runs.size)
        assertEquals('2', runs[0].code)
        assertEquals(3, runs[0].minutes)
        assertEquals('1', runs[1].code)
        assertEquals(3 * m, runs[1].startMs)
    }

    @Test
    fun `a run's end is exclusive, so consecutive runs abut exactly`() {
        val runs = SleepShape.runs(SleepSegmentInput(0, 4, "1122"))
        assertEquals(runs[0].endMs, runs[1].startMs)
        assertEquals(4 * m, runs.last().endMs)
    }

    @Test
    fun `segments of one night stitch into one session`() {
        // The band splits a night at 120 minutes; the pieces must come back together.
        val night = listOf(
            SleepSegmentInput(0, 120, "1".repeat(120)),
            SleepSegmentInput(120 * m, 120, "2".repeat(120)),
            SleepSegmentInput(240 * m, 60, "3".repeat(60)),
        )
        val sessions = SleepShape.sessions(night)
        assertEquals(1, sessions.size)
        assertEquals(300, sessions.single().totalMinutes)
    }

    @Test
    fun `a nap hours later is its own session, not one very long sleep`() {
        val segments = listOf(
            SleepSegmentInput(0, 60, "2".repeat(60)),
            SleepSegmentInput(300 * m, 30, "2".repeat(30)),   // five hours later
        )
        assertEquals(2, SleepShape.sessions(segments).size)
    }

    @Test
    fun `segments arriving out of order are sorted, not trusted`() {
        val segments = listOf(
            SleepSegmentInput(120 * m, 60, "2".repeat(60)),
            SleepSegmentInput(0, 120, "1".repeat(120)),
        )
        val s = SleepShape.sessions(segments).single()
        assertEquals(0L, s.startMs)
        assertEquals(180, s.totalMinutes)
    }

    @Test
    fun `a real night's stage totals reproduce`() {
        // 2026-08-04: 718 minutes — deep 156, light 387, REM 121, awake 54.
        val stages = buildString {
            append("1".repeat(156)); append("2".repeat(387))
            append("3".repeat(121)); append("5".repeat(54))
        }
        val s = SleepShape.sessions(listOf(SleepSegmentInput(0, stages.length, stages))).single()
        assertEquals(718, s.totalMinutes)
        assertEquals(156, s.deep)
        assertEquals(387, s.light)
        assertEquals(121, s.rem)
        assertEquals(54, s.awake)
        assertEquals((156 + 121) / 718.0, s.deepRemShare!!, 1e-9)
    }

    @Test
    fun `awake minutes stay in the denominator — they really do make a night less restorative`() {
        val calm = SleepShape.sessions(
            listOf(SleepSegmentInput(0, 100, "1".repeat(50) + "2".repeat(50))),
        ).single()
        val broken = SleepShape.sessions(
            listOf(SleepSegmentInput(0, 100, "1".repeat(50) + "5".repeat(50))),
        ).single()
        assertTrue(
            "an hour lying awake must lower the restorative share",
            broken.deepRemShare!! < calm.deepRemShare!! + 1e-9,
        )
        assertEquals(0.5, broken.deepRemShare!!, 1e-9)
    }

    @Test
    fun `an unobserved stage code is carried as unknown rather than assumed away`() {
        val s = SleepShape.sessions(listOf(SleepSegmentInput(0, 4, "1144"))).single()
        assertEquals(4, s.totalMinutes)
        assertEquals(2, s.minutesOf('4'))
        assertEquals("Unknown", SleepShape.labelOf('4').en)
        assertEquals("不明", SleepShape.labelOf('4').ja)
        assertEquals(SleepShape.ROWS.size, SleepShape.rowOf('4'))
    }

    @Test
    fun `rows stack with deep at the bottom`() {
        assertEquals(0, SleepShape.rowOf('5'))   // awake, top
        assertEquals(3, SleepShape.rowOf('1'))   // deep, bottom
    }

    @Test
    fun `an empty stage string yields nothing rather than a zero-length run`() {
        assertTrue(SleepShape.runs(SleepSegmentInput(0, 0, "")).isEmpty())
        assertTrue(SleepShape.sessions(emptyList()).isEmpty())
    }
}
