package com.opentasker.core.huawei.pgnss

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The almanac fallback, written from the outage that made it necessary.
 *
 * On the evening of 2026-09-14 ESA's GSSC stopped answering — three connection failures and a 404
 * across four dates, two hours after it had served fine — and two builds in a row died with "no
 * Galileo almanac XML in the last 10 days". Nothing else was wrong: the orbits were downloaded, the
 * navigation files were downloaded, the arithmetic was ready, and one unavailable almanac threw the
 * lot away. 白い熊 asked the question that matters about that state — *"How will we know when it's
 * back?"* — and the answer has to be that nobody needs to.
 *
 * **The orbit products deliberately have no such fallback.** A cached orbit is a WRONG orbit a day
 * later; an almanac is coarse elements, published every few days and useful for weeks. The two are
 * not the same kind of input and must not get the same treatment.
 */
class AlmanacCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private fun today() = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun fetcher(cache: File?) =
        PgnssFetcher(temp.newFolder(), PgnssFetcher.defaultClient(), cache)

    /** A live fetch: writes the file the real one would and hands it back. */
    private fun live(name: String, text: String): () -> File = {
        File(temp.root, name).apply { writeText(text) }
    }

    @Test
    fun `a good fetch is cached under the day it was taken and the name it was published under`() {
        val cache = temp.newFolder("almanac")
        val got = fetcher(cache).almanac("galileo", live("galileo_2026-09-11.xml", "<almanac/>"))
        assertEquals("<almanac/>", got.readText())
        val kept = cache.listFiles()!!.single()
        assertEquals("galileo.${today()}.galileo_2026-09-11.xml", kept.name)
    }

    @Test
    fun `the source going down is served from the cache, and the run says so`() {
        val cache = temp.newFolder("almanac")
        File(cache, "galileo.${today()}.galileo_2026-09-11.xml").writeText("<cached/>")
        val f = fetcher(cache)
        val got = f.almanac("galileo") { throw IOException("no Galileo almanac XML in the last 10 days") }
        assertEquals("<cached/>", got.readText())
        // The published name travels, because the limit bounds the CACHE and the name shows the
        // vintage — the live fetch may itself have walked back days to find that file.
        assertEquals("galileo_2026-09-11.xml", got.name)
    }

    @Test
    fun `a cache older than the limit is refused rather than served`() {
        val cache = temp.newFolder("almanac")
        val old = LocalDate.now(ZoneOffset.UTC)
            .minusDays(PgnssFetcher.MAX_ALMANAC_AGE_DAYS + 1)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        File(cache, "galileo.$old.galileo_old.xml").writeText("<stale/>")
        val error = assertThrows(IOException::class.java) {
            fetcher(cache).almanac("galileo") { throw IOException("down") }
        }
        assertTrue(error.message!!.contains("the limit is"))
    }

    @Test
    fun `with no cache at all it fails exactly as it did before`() {
        val error = assertThrows(IOException::class.java) {
            fetcher(null).almanac("galileo") { throw IOException("down") }
        }
        assertTrue(error.message!!.contains("nothing is cached"))
    }

    @Test
    fun `only the newest of each kind is kept, and the kinds do not tread on each other`() {
        val cache = temp.newFolder("almanac")
        File(cache, "galileo.2026-01-01.galileo_old.xml").writeText("old")
        File(cache, "gps-yuma.2026-01-01.current_yuma.alm").writeText("yuma")
        fetcher(cache).almanac("galileo", live("galileo_2026-09-14.xml", "new"))
        val names = cache.listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf("galileo.${today()}.galileo_2026-09-14.xml", "gps-yuma.2026-01-01.current_yuma.alm"),
            names,
        )
    }
}
