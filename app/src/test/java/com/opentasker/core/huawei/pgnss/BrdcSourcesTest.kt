package com.opentasker.core.huawei.pgnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast navigation sources, and why there are five of them across four organisations.
 *
 * On 2026-09-14 six builds in a row died because `igs.bkg.bund.de` was returning empty replies, and
 * the set on 白い熊's band went a day stale for want of a file that three other data centres were
 * serving at that moment. The orbit product has had mirrors since the day it was written; this one,
 * which is just as necessary, had one host.
 *
 * **BKG was never down.** It runs a second machine at another address — measured that same evening
 * at 10/10 on listings and 6/6 on fetches, byte-identical files, while the HTTPS gateway managed
 * 3/10. One hostname was mistaken for an organisation, and 白い熊 said so before the measurement
 * did. These tests exist so the list cannot quietly shrink back to one.
 */
class BrdcSourcesTest {

    @Test
    fun `there are several sources and they are not all one organisation`() {
        assertTrue(
            "one host is not an architecture, found ${PgnssFetcher.BRDC_SOURCES.size}",
            PgnssFetcher.BRDC_SOURCES.size >= 4,
        )
        val organisations = PgnssFetcher.BRDC_SOURCES
            .map { it.name.substringBefore(' ') }
            .toSet()
        assertTrue(
            "mirrors of one archive are not redundancy, found $organisations",
            organisations.size >= 3,
        )
    }

    @Test
    fun `the same-day product is among them and is named as such`() {
        // `BRDC00WRD_R` is the ONLY merged, global, same-day mixed navigation file with a GPS
        // Klobuchar block that a sweep of every public data centre could find. Losing it from this
        // list would put today's ephemeris back on the hourly stations alone.
        assertTrue(PgnssFetcher.BRDC_SOURCES.any { it.name.contains("WRD") })
        assertTrue(PgnssFetcher.BRDC_SOURCES.any { it.name.contains("same-day") })
    }

    @Test
    fun `the hourly fallback asks for a few stations, not a constellation of them`() {
        // Three reproduced the merged file's satellites in the 2026-09-14 measurement; more is a
        // hundred kilobytes each for satellites already carried.
        assertEquals(3, PgnssFetcher.HOURLY_STATIONS_WANTED)
        assertTrue(PgnssFetcher.HOURLY_LOOK_BACK >= 2)
    }

    @Test
    fun `a server saying no is not retried, and a broken wire is`() {
        assertTrue(PgnssFetcher.TRANSPORT_ATTEMPTS >= 2)
        // The distinction itself: `SourceRefused` carries the code the server gave, and
        // `PgnssFetcher.retrying` rethrows it without a second attempt. A 404 on a daily product
        // that is not published yet is the ORDINARY case, and retrying it costs seconds per build
        // across two products, five sources and three days.
        val refused = SourceRefused(404, "a file that is not published yet")
        assertEquals(404, refused.code)
        assertTrue(refused.message!!.contains("404"))
    }
}
