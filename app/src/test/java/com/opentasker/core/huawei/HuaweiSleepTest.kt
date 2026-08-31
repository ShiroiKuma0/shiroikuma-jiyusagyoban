package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep decoder, checked against a real night AND against what the band itself displayed for it.
 *
 * The fixture is 白い熊's night of 2026-08-21, pulled off the band on 2026-08-22. Its ground truth
 * is a photograph of the band's own Sleep screen: bed time 23:55, woke 05:03, night sleep 4 h 50 min,
 * deep 1 h 23 min, light 2 h 37 min, REM 50 min.
 *
 * That screen is what makes this a test rather than a restatement of the parser. Without it the
 * stage numbering would be a guess, and the plausible guess is wrong: the codes do not run from
 * deep to light, so an unchecked decoder would swap deep with light and still draw a convincing
 * hypnogram.
 *
 * NOTE the fixture is 642 bytes — captured before the client stopped trusting the band's declared
 * size, so its final pair is seven bytes rather than eight. That is deliberate: the tolerant path
 * for a short final segment is exercised by the very file that revealed the problem.
 */
class HuaweiSleepTest {

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/huawei/sleep-2026-08-21.bin")) {
            "the captured night is missing from test resources"
        }.readBytes()

    @Test
    fun `the session spans the night the band reported`() {
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        // 2026-08-21 23:55:00 -> 2026-08-22 05:03:00 local, as printed on the band.
        assertEquals(1_787_349_300L, s.startSeconds)
        assertEquals(1_787_367_780L, s.endSeconds)
        assertEquals(18_480L, s.endSeconds - s.startSeconds)
    }

    @Test
    fun `stage totals match the band's own screen to the minute`() {
        val t = checkNotNull(HuaweiSleep.parse(fixture())).totals()
        assertEquals(157, t.getValue(HuaweiSleep.Stage.LIGHT) / 60)   // screen: 2 h 37 min
        assertEquals(50, t.getValue(HuaweiSleep.Stage.REM) / 60)      // screen: 50 min
        assertEquals(83, t.getValue(HuaweiSleep.Stage.DEEP) / 60)     // screen: 1 h 23 min
    }

    @Test
    fun `light plus REM plus deep is the headline night sleep`() {
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        assertEquals(290, s.asleepSeconds / 60)                        // screen: 4 h 50 min
    }

    /** The same night re-pulled after the client stopped trusting the declared size: 643 bytes. */
    private fun fullFixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/huawei/sleep-2026-08-21-full.bin")) {
            "the 643-byte capture is missing from test resources"
        }.readBytes()

    @Test
    fun `segments run consecutively and abut without gaps`() {
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        s.segments.zipWithNext { a, b -> assertEquals(a.endSeconds, b.startSeconds) }
        assertTrue(s.segments.all { it.durationSeconds % 60 == 0 })
    }

    @Test
    fun `the timeline is anchored by the first non-awake segment, not the header start`() {
        // The header brackets the SLEEP. This night's segments run 324 minutes against a declared
        // span of 308: a 12-minute awake block before bed time and a 4-minute one after waking.
        // Anchoring at the header start instead leaves every total correct and shifts the whole
        // hypnogram twelve minutes late — a failure no summary figure would show.
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        assertEquals(s.startSeconds - 12 * 60, s.segments.first().startSeconds)
        val firstSleep = s.segments.first { it.stage != HuaweiSleep.Stage.AWAKE }
        assertEquals(s.startSeconds, firstSleep.startSeconds)
    }

    @Test
    fun `the last non-awake segment ends exactly on the band's wake time`() {
        // The file's own consistency check, and the reason the anchoring above can be trusted:
        // it is derived from the FRONT of the array and confirmed at the BACK.
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        assertTrue(s.alignsWithHeader)
        assertEquals(
            s.endSeconds,
            s.segments.last { it.stage != HuaweiSleep.Stage.AWAKE }.endSeconds,
        )
    }

    @Test
    fun `the clamped and full captures of the same night decode identically`() {
        // 642 bytes against 643 — the same night before and after the client stopped discarding the
        // byte past the declared size. The recovered byte was the final segment's high byte, and it
        // was zero, so the tolerant path had already produced the right answer. That it was zero is
        // luck, not design, which is exactly why this pins both.
        val short = checkNotNull(HuaweiSleep.parse(fixture()))
        val full = checkNotNull(HuaweiSleep.parse(fullFixture()))
        assertEquals(short.segments, full.segments)
        assertEquals(short.totals(), full.totals())
        assertEquals(18, full.segments.size)
    }

    @Test
    fun `the awake block near the end is where the band drew one`() {
        // The hypnogram has exactly one yellow bar close to the right-hand edge. That it falls in
        // the same place here is an independent check on the stage mapping — one that does not go
        // through the totals at all.
        val s = checkNotNull(HuaweiSleep.parse(fixture()))
        val awake = s.segments.withIndex().filter { it.value.stage == HuaweiSleep.Stage.AWAKE }
        assertTrue("expected a late awake block", awake.any { it.index >= s.segments.size - 3 })
    }

    @Test
    fun `rubbish is refused rather than half-decoded`() {
        assertNull(HuaweiSleep.parse(ByteArray(0)))
        assertNull(HuaweiSleep.parse(ByteArray(700)))
        // A plausible header whose container tag is wrong must not fall through to the segments.
        val bad = fixture().copyOf()
        bad[0x41] = 0x77
        assertNull(HuaweiSleep.parse(bad))
    }

    @Test
    fun `an unknown stage code survives as UNKNOWN rather than being dropped`() {
        assertEquals(HuaweiSleep.Stage.UNKNOWN, HuaweiSleep.Stage.of(9))
        assertEquals(HuaweiSleep.Stage.DEEP, HuaweiSleep.Stage.of(3))
    }

    @Test
    fun `the decoder is not simply echoing a stored total`() {
        // The file contains no totals: 290, 308, 83, 157 and 50 appear nowhere in it, at any width.
        // Health computes the summary, and so must we — this pins that the match above is arithmetic
        // over segments rather than a number lifted out of a header.
        val d = fixture()
        for (v in intArrayOf(290, 308, 83, 157, 50)) {
            for (w in intArrayOf(2, 4)) {
                val needle = ByteArray(w) { i -> (v shr (8 * (w - 1 - i))).toByte() }
                assertTrue(
                    "total $v should not be stored in the file",
                    (0..d.size - w).none { off -> (0 until w).all { d[off + it] == needle[it] } },
                )
            }
        }
    }
}
