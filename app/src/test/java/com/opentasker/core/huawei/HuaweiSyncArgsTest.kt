package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window walk.
 *
 * This is the heaviest test file in the Huawei tree on purpose: it pins a firmware quirk that costs
 * error 106489 when it is got wrong, the failure is intermittent (it only bites once a gap exceeds a
 * day), and this is the only place that knowledge is written down.
 */
class HuaweiSyncArgsTest {

    private val hour = 3_600L
    private val day = 24 * hour
    private val now = 1_787_000_000L

    private fun resolve(
        from: HuaweiFrom,
        last: Long? = null,
        overlap: Int = 30,
        maxWindows: Int = HuaweiSyncArgs.DEFAULT_MAX_WINDOWS,
    ) = HuaweiSyncArgs.resolve(from, last, overlap, now, maxWindows = maxWindows)

    private fun assertWellFormed(windows: List<LongRange>) {
        assertTrue("never empty", windows.isNotEmpty())
        assertEquals("newest window must end now", now, windows.first().last)
        windows.forEach {
            assertTrue("no negative range: $it", it.first <= it.last)
            assertTrue("no window may exceed 24 h: $it", it.last - it.first <= day)
        }
        // Consecutive windows share their boundary second by design — see HuaweiSyncArgs.
        windows.zipWithNext { newer, older ->
            assertEquals("windows must be contiguous", newer.first, older.last)
        }
    }

    @Test
    fun `a first sync asks for exactly one window`() {
        val w = resolve(HuaweiFrom.Auto, last = null)
        assertEquals(1, w.size)
        assertEquals(day, w[0].last - w[0].first)
        assertWellFormed(w)
    }

    @Test
    fun `a three-day gap becomes contiguous 24 h windows, newest first`() {
        // Newest first matters: if the run is cut short, what survives is the data most likely to
        // fall off the band's ring buffer first.
        val w = resolve(HuaweiFrom.Auto, last = now - 3 * day, overlap = 0)
        assertEquals(3, w.size)
        assertWellFormed(w)
        assertTrue("newest first", w[0].first > w[1].first)
    }

    @Test
    fun `the overlap is applied once, at the oldest edge`() {
        val plain = resolve(HuaweiFrom.Auto, last = now - 3 * day, overlap = 0)
        val lapped = resolve(HuaweiFrom.Auto, last = now - 3 * day, overlap = 30)
        assertEquals("overlap must not shift the recent windows", plain[0], lapped[0])
        assertEquals(plain.last().first - 30 * 60, lapped.last().first)
        assertWellFormed(lapped)
    }

    @Test
    fun `a backwards clock step yields one ordinary window, never a negative range`() {
        // A band whose clock we set, against a phone whose clock moved: this must not explode.
        val w = resolve(HuaweiFrom.Auto, last = now + 10 * day)
        assertEquals(1, w.size)
        assertWellFormed(w)
    }

    @Test
    fun `a last success in the immediate past still produces a window`() {
        val w = resolve(HuaweiFrom.Auto, last = now, overlap = 0)
        assertEquals(1, w.size)
        assertWellFormed(w)
    }

    @Test
    fun `a huge gap is capped rather than holding the radio for hours`() {
        val w = resolve(HuaweiFrom.Auto, last = now - 400 * day, maxWindows = 14)
        assertEquals(14, w.size)
        assertWellFormed(w)
        assertTrue("the cap must keep the NEWEST data", w[0].last == now)
    }

    @Test
    fun `All walks back the full cap — this is the ring-depth probe`() {
        val w = resolve(HuaweiFrom.All, maxWindows = 5)
        assertEquals(5, w.size)
        assertWellFormed(w)
        assertEquals(5 * day, w.first().last - w.last().first)
    }

    @Test
    fun `Since names its own start and takes no overlap`() {
        val w = resolve(HuaweiFrom.Since(now - 2 * day), overlap = 30)
        assertWellFormed(w)
        assertEquals("Since must not be widened by the overlap", now - 2 * day, w.last().first)
    }

    @Test
    fun `every window is counted separately, so none may exceed the workable span`() {
        // The whole reason this file exists: an open-ended count window makes record 1 fail with
        // 106489, while the identical request inside a 24 h window succeeds.
        listOf(1L, 3L, 9L, 40L).forEach { days ->
            val w = resolve(HuaweiFrom.Auto, last = now - days * day, overlap = 0)
            assertWellFormed(w)
        }
    }

    @Test
    fun `parseFrom defaults to Auto and never throws`() {
        assertEquals(HuaweiFrom.Auto, HuaweiSyncArgs.parseFrom(null))
        assertEquals(HuaweiFrom.Auto, HuaweiSyncArgs.parseFrom(""))
        assertEquals(HuaweiFrom.Auto, HuaweiSyncArgs.parseFrom("  auto "))
        assertEquals(HuaweiFrom.Auto, HuaweiSyncArgs.parseFrom("nonsense"))
        assertEquals(HuaweiFrom.All, HuaweiSyncArgs.parseFrom("all"))
        assertEquals(HuaweiFrom.All, HuaweiSyncArgs.parseFrom("0"))
        assertEquals(HuaweiFrom.Since(1_787_000_000L), HuaweiSyncArgs.parseFrom("1787000000"))
    }
}
