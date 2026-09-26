package com.opentasker.core.huawei.pgnss

import com.opentasker.ProductionSources
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How old an almanac is, by the date its own publisher stamped it.
 *
 * The age is the whole point: on 2026-09-25 a build shipped a Galileo almanac published three days
 * earlier, and against the ephemeris in its own set its worst satellites sat **2 227 km** out where
 * a current almanac's worst sits 96 km. The set went to the band, the band searched the wrong sky,
 * and there was no fix. Everything about that was knowable at build time and none of it was said.
 */
class AlmanacVintageTest {

    private val fetcher = PgnssFetcher(File("/tmp"), cacheDir = null)

    /** Three publishers, three conventions, and one of them has no date at all. */
    @Test
    fun `each publisher's own naming is read`() {
        assertEquals(LocalDate.of(2026, 9, 22), fetcher.vintageOf("galileo_2026-09-22.xml"))
        assertEquals(LocalDate.of(2026, 9, 18), fetcher.vintageOf("MCCT_260918.agl"))
        // Republished under one unchanging name, which is exactly why GPS is never the stale one.
        assertEquals(LocalDate.now(ZoneOffset.UTC), fetcher.vintageOf("current_yuma.alm"))
    }

    /** An unreadable name costs the age, never the build. */
    @Test
    fun `a name that carries no date is not a failure`() {
        assertNull(fetcher.vintageOf("something-else.bin"))
    }

    /**
     * THE CALLS MUST EXIST, and this test exists because once they did not.
     *
     * The first version of this work defined `vintageOf` and `recordAge` and wired neither: an
     * edit aborted halfway, the helpers landed, the call sites did not, and the build shipped with
     * `%HUAWEI_PgnssAlmanac` quietly empty — which reads exactly like "nothing to report" rather
     * than like a feature that is not connected (白い熊 caught it on the panel the same evening).
     */
    @Test
    fun `the age is actually recorded on both paths and handed out with the sources`() {
        val src = ProductionSources.read("com/opentasker/core/huawei/pgnss/Fetch.kt")
        assertTrue(
            "the live path records the vintage it fetched",
            "liveVintage?.let { recordAge(kind, it) }" in src,
        )
        assertTrue(
            "and so does the fallback, or a cached almanac would report no age at all",
            "vintageOf(published)?.let { recordAge(kind, it) }" in src,
        )
        assertTrue(
            "the fresher of live and cache wins — a flaky morning must not overwrite a newer copy",
            "if (cachedVintage != null && liveVintage != null && cachedVintage.isAfter(liveVintage))" in src,
        )
        assertTrue(
            "and the ages leave the fetcher with the sources",
            "almanacAgeDays = almanacAges.toMap()" in src,
        )
        val build = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        for (published in listOf("PgnssAlmanac", "PgnssAlmanacDays", "PgnssAlmanacHave")) {
            assertTrue("$published has to reach the panel", "\"\${prefix}$published\"" in build)
        }
    }

    /** One day is ordinary; the line falls below what was measured to cost kilometres. */
    @Test
    fun `the staleness bound sits between a day and the three that were measured`() {
        assertEquals(1L, PgnssSources.ALMANAC_STALE_DAYS)
    }
}
