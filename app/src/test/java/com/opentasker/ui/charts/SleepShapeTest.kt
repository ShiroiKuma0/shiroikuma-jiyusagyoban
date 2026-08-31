package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a half-hour hole in the middle of the night does not cut the night in two`() {
        // 2026-08-20, from the archive, minute-for-minute: the band recorded 22:30:57-00:00:57,
        // 00:04:57-02:04:57, 02:04:57-04:00:57, then stopped for TWENTY-NINE MINUTES before
        // 04:29:57-06:08:57. At the old 20-minute tolerance that hole ended the night, so the
        // screen announced the 1h39m tail as 白い熊's sleep for the day. One night, 425 minutes.
        val night = listOf(
            SleepSegmentInput(0, 90, "2".repeat(90)),               // 22:30 -> 00:00
            SleepSegmentInput(94 * m, 120, "2".repeat(120)),        // 00:04 -> 02:04, 4m hole
            SleepSegmentInput(214 * m, 116, "2".repeat(116)),       // 02:04 -> 04:00, abutting
            SleepSegmentInput(359 * m, 99, "2".repeat(99)),         // 04:29 -> 06:08, 29m hole
        )
        val s = SleepShape.sessions(night).single()
        assertEquals(425, s.totalMinutes)
        assertEquals(0L, s.startMs)
        assertEquals(458 * m, s.endMs)   // the extent spans the holes; the total does not count them
    }

    @Test
    fun `the tolerance sits in the empty band between a hole and a separate sleep`() {
        // Measured over 2026-08: intra-night holes reach 29 minutes, while the shortest gap that
        // really separates two sleeps is 197. A threshold on either edge of that band is a
        // threshold with no margin, which is how the 20-minute one failed.
        assertTrue(SleepShape.STITCH_TOLERANCE_MS > 29 * m)
        assertTrue(SleepShape.STITCH_TOLERANCE_MS < 197 * m)
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

    // ---- which session is "the night" ----------------------------------------------------------
    //
    // 白い熊, 2026-08-20: "so no day naps replace nights". The headline, the stage table, the 健康指数
    // and the 回復 card all read one session, and it used to be whichever ended last -- which after a
    // nap is the nap. The times below are 白い熊's own, from the 2026-08 archive.

    /** Session running from [fromHour] for [minutes], on an arbitrary day, in UTC. */
    private fun sessionAt(fromHour: Int, fromMinute: Int = 0, minutes: Int): SleepSession {
        val dayStart = 1_787_184_000_000L   // 2026-08-20 00:00 UTC
        val start = dayStart + (fromHour * 60L + fromMinute) * m
        return SleepShape.sessions(
            listOf(SleepSegmentInput(start, minutes, "2".repeat(minutes))),
        ).single()
    }

    private val utc = java.time.ZoneOffset.UTC

    @Test
    fun `an afternoon nap is not a night`() {
        // 2026-08-04 15:25 -> 17:20, and 2026-08-15 15:09 -> 16:36.
        assertFalse(SleepShape.isNight(sessionAt(15, 25, minutes = 115), utc))
        assertFalse(SleepShape.isNight(sessionAt(15, 9, minutes = 87), utc))
    }

    @Test
    fun `an early-evening doze is not a night either`() {
        // 2026-08-02 18:56 -> 19:26 is the nap that sits CLOSEST to the window; it stays outside.
        assertFalse(SleepShape.isNight(sessionAt(18, 56, minutes = 30), utc))
        assertFalse(SleepShape.isNight(sessionAt(17, 53, minutes = 35), utc))
    }

    @Test
    fun `a late-morning doze is not a night`() {
        // 2026-08-01 11:15 -> 11:48.
        assertFalse(SleepShape.isNight(sessionAt(11, 15, minutes = 33), utc))
    }

    @Test
    fun `an ordinary night is a night`() {
        // 2026-08-19 22:30 -> 06:08, and the longest on record, 08-04 20:51 -> 07:30.
        assertTrue(SleepShape.isNight(sessionAt(22, 30, minutes = 458), utc))
        assertTrue(SleepShape.isNight(sessionAt(20, 51, minutes = 639), utc))
    }

    @Test
    fun `a short broken night is still a night`() {
        // 2026-08-04 03:21 -> 06:19: 178 minutes, UNDER RecoverySource.MIN_NIGHT_MINUTES. This is the
        // case a duration test gets wrong and the reason the rule is about time of day.
        val short = sessionAt(3, 21, minutes = 178)
        assertTrue(short.totalMinutes < RecoverySource.MIN_NIGHT_MINUTES)
        assertTrue(SleepShape.isNight(short, utc))
    }

    @Test
    fun `a nap after the night does not take the headline`() {
        // The exact shape of the complaint: last night, then a doze this afternoon. Before the fix
        // the nap ended later and therefore won.
        val night = sessionAt(-2, 30, minutes = 458)   // 21:30 the previous evening -> 05:08
        val nap = sessionAt(15, 0, minutes = 90)
        val picked = SleepShape.latestNight(listOf(night, nap), utc)
        assertEquals(night, picked)
        assertTrue(nap.endMs > night.endMs)            // the nap really is the later session
    }

    @Test
    fun `the most recent night wins among nights`() {
        val older = sessionAt(-26, 0, minutes = 400)
        val newer = sessionAt(-2, 0, minutes = 400)
        assertEquals(newer, SleepShape.latestNight(listOf(older, newer), utc))
    }

    @Test
    fun `nothing but naps yields no night rather than the longest nap`() {
        // A dash is the truthful answer; falling back to "the latest session" would reintroduce
        // exactly the bug this prevents.
        val naps = listOf(sessionAt(13, 0, minutes = 60), sessionAt(16, 0, minutes = 120))
        assertNull(SleepShape.latestNight(naps, utc))
        assertNull(SleepShape.latestNight(emptyList(), utc))
    }

    @Test
    fun `the window is stated in minutes of the day`() {
        assertTrue(SleepShape.isNightMidpoint(21 * 60))        // 21:00, first minute in
        assertFalse(SleepShape.isNightMidpoint(21 * 60 - 1))   // 20:59, last minute out
        assertTrue(SleepShape.isNightMidpoint(9 * 60 - 1))     // 08:59, last minute in
        assertFalse(SleepShape.isNightMidpoint(9 * 60))        // 09:00, first minute out
        assertTrue(SleepShape.isNightMidpoint(0))              // midnight
    }

    @Test
    fun `every session in the 2026-08 archive is classified correctly`() {
        // Every midpoint in the archive, in minutes of the day: 19 nights spanning 01:27 to 04:50,
        // and 5 naps from 11:32 to 19:11. Nothing at all lies between 04:50 and 11:32 — that empty
        // stretch is the margin the rule sits in, and these are the numbers that say so.
        val nightMidpoints = listOf(
            87, 89, 116, 129, 131, 135, 138, 139, 159, 163, 164, 171, 178, 180, 182, 199, 210, 231, 290,
        )
        assertEquals(19, nightMidpoints.size)
        nightMidpoints.forEach { assertTrue("$it should be a night", SleepShape.isNightMidpoint(it)) }
        val napMidpoints = listOf(692, 953, 983, 1091, 1151)
        assertEquals(5, napMidpoints.size)
        napMidpoints.forEach { assertFalse("$it should be a nap", SleepShape.isNightMidpoint(it)) }
    }

    @Test
    fun `an empty stage string yields nothing rather than a zero-length run`() {
        assertTrue(SleepShape.runs(SleepSegmentInput(0, 0, "")).isEmpty())
        assertTrue(SleepShape.sessions(emptyList()).isEmpty())
    }
}
