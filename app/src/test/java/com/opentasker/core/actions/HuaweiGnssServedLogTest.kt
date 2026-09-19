package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The record of what the BAND took, and until when its forecast is good.
 *
 * ## Why this exists
 *
 * Everything this feature wrote down was about the phone. `built-log.txt` says what was built and
 * `GnssSummary` says what was offered; nothing said what the band **accepted**, and that omission
 * cost 白い熊 four days in September 2026.
 *
 * On 2026-09-18 a set was built — window to 09-21 11:59 UTC, six files, 780 KB — and the transfer
 * after it moved **zero bytes**. The band went on wearing the set built on 09-15, whose window had
 * closed at 09-18 13:59 UTC, and did what a band with a dead set does: marked its data current,
 * stopped asking for broadcast ephemeris, and fixed only minutes into the walk. The phone was in
 * perfect health and said so. Three separate theories about stale almanacs were built on that
 * silence, all of them wrong; the real inputs had every almanac published the same day it was used.
 *
 * Re-running the transfer on 09-19 restored an **8 second** fix — so nothing was wrong with the
 * data, and the one fact needed to see that had never been written down.
 */
class HuaweiGnssServedLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val silent: (String) -> Unit = {}

    @Test
    fun `a transfer that took nothing is written down as loudly as one that worked`() {
        val dir = tmp.newFolder("gnss")
        HuaweiGnssAction.recordServed(dir, "TOOK NOTHING · offered 7 · the band never asked", silent)
        HuaweiGnssAction.recordServed(dir, "took 6 file(s), 799112 B · offered 7", silent)

        val lines = dir.resolve(HuaweiGnssAction.SERVED_NAME).readLines().filter { it.isNotBlank() }
        assertEquals("one line per attempt, in order", 2, lines.size)
        assertTrue("the failure is kept, not only the success", lines[0].contains("TOOK NOTHING"))
        assertTrue("and the success after it", lines[1].contains("799112 B"))
        assertTrue("every line is stamped in UTC", lines.all { it.contains(" UTC · ") })
    }

    @Test
    fun `the log is capped so months of daily transfers stay a few kilobytes`() {
        val dir = tmp.newFolder("gnss")
        repeat(HuaweiGnssAction.SERVED_LINES + 25) { HuaweiGnssAction.recordServed(dir, "run $it", silent) }

        val lines = dir.resolve(HuaweiGnssAction.SERVED_NAME).readLines().filter { it.isNotBlank() }
        assertEquals(HuaweiGnssAction.SERVED_LINES, lines.size)
        assertTrue("the newest survives", lines.last().endsWith("run ${HuaweiGnssAction.SERVED_LINES + 24}"))
        assertTrue("the oldest is dropped", lines.none { it.endsWith("run 0") })
    }

    @Test
    fun `what the band holds survives the run that put it there`() {
        val dir = tmp.newFolder("gnss")
        val until = System.currentTimeMillis() + 47 * 3_600_000L
        HuaweiGnssAction.writeBandHolds(dir, until, silent)

        assertEquals(
            "read back by a LATER run, which is the only kind that needs it",
            until, HuaweiGnssAction.bandHoldsUntil(dir),
        )
    }

    /**
     * The file is meant to be read by eye as well as by code — the number first, then the same
     * instant spelled out, because someone looking at the store during a bad walk should not have
     * to convert epoch milliseconds in their head.
     */
    @Test
    fun `the remembered window is legible on the page`() {
        val dir = tmp.newFolder("gnss")
        // The real window of the set built 2026-09-18 that the band did not take.
        HuaweiGnssAction.writeBandHolds(dir, 1_789_991_940_000L, silent)

        val text = dir.resolve(HuaweiGnssAction.BAND_HOLDS_NAME).readText()
        assertTrue("the machine-readable half comes first", text.startsWith("1789991940000\t"))
        assertTrue("and a human one follows, in UTC", text.contains("2026-09-21 11:59 UTC"))
    }

    /** A band that has never been seen taking a forecast answers null, not zero. */
    @Test
    fun `a store with no record answers null rather than the epoch`() {
        assertNull(HuaweiGnssAction.bandHoldsUntil(tmp.newFolder("gnss")))
    }

    /** A truncated or hand-edited file must not read as 1970, which would look like an expiry. */
    @Test
    fun `a corrupt record answers null rather than a date in 1970`() {
        val dir = tmp.newFolder("gnss")
        dir.resolve(HuaweiGnssAction.BAND_HOLDS_NAME).writeText("not a number\tand not a date\n")
        assertNull(HuaweiGnssAction.bandHoldsUntil(dir))
    }
}
