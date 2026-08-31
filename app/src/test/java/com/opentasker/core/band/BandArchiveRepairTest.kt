package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The archive repair, against the shape of the real failure it was written for.
 *
 * On 白い熊's own archive, syncs **28** and **41** were absent entirely — no header, no records, no
 * census — while their rows sat in the database. The engine wrote the file on its success path only,
 * so a sync that landed rows and then threw took its lines with it. The scan below is what finds
 * that: a sync id present in the database and absent from every census line in the file.
 */
class BandArchiveRepairTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun census(id: Long) = """{"t":"census","id":$id,"ok":true,"ms":7000,"streams":{}}"""
    private fun sample(ts: Long, sid: Long) =
        """{"t":"s","m":"hr","ts":$ts,"e":1785775410000,"v":65.0,"sid":$sid}"""

    @Test
    fun `census ids are read out of a file of mostly samples`() {
        val file = folder.newFile("band_2026-08.jsonl")
        file.writeText(
            buildString {
                appendLine("""{"t":"sync","id":27,"at":"x","zone":"Europe/Prague","addr":"a","from":1,"src":"action"}""")
                repeat(500) { appendLine(sample(20260808120000L + it, 27)) }
                appendLine(census(27))
                appendLine(census(29))
            },
        )
        assertEquals(setOf(27L, 29L), BandArchiveRepair.archivedSyncIds(listOf(file)))
    }

    @Test
    fun `a repair marker counts as archived, so a repaired sync is never repaired twice`() {
        val file = folder.newFile("band_2026-08.jsonl")
        file.writeText(
            """
            ${census(27)}
            {"t":"repair","at":"2026-08-09T18:00:00+02:00","ids":[28,41],"n":29,"v":1}
            ${census(42)}
            """.trimIndent(),
        )
        assertEquals(setOf(27L, 28L, 41L, 42L), BandArchiveRepair.archivedSyncIds(listOf(file)))
    }

    /** A kill mid-write leaves a torn final line. Every reader of this archive drops it; so does this. */
    @Test
    fun `a torn line is skipped rather than derailing the scan`() {
        val file = folder.newFile("band_2026-08.jsonl")
        file.writeText(census(27) + "\n" + """{"t":"cens""" + "\n" + census(30) + "\n")
        assertEquals(setOf(27L, 30L), BandArchiveRepair.archivedSyncIds(listOf(file)))
    }

    @Test
    fun `only the monthly files count — a full export is a snapshot, not where syncs append`() {
        folder.newFile("band_2026-07.jsonl").writeText(census(1) + "\n")
        folder.newFile("band_2026-08.jsonl").writeText(census(27) + "\n")
        folder.newFile("band_full_2026-08-09_18-00-00.jsonl").writeText(census(99) + "\n")
        folder.newFile("notes.txt").writeText("ignore me\n")

        val files = BandArchiveRepair.monthlyFiles(folder.root)
        assertEquals(listOf("band_2026-07.jsonl", "band_2026-08.jsonl"), files.map { it.name })
        assertEquals(202607, BandArchiveRepair.oldestMonth(files))
        assertEquals(setOf(1L, 27L), BandArchiveRepair.archivedSyncIds(files))
    }

    @Test
    fun `oldestMonth is null when there is no monthly file at all`() {
        assertNull(BandArchiveRepair.oldestMonth(emptyList()))
    }

    /** The real case: 28 and 41 in the DB, absent from every census. */
    @Test
    fun `syncs with no census in the window are reported missing`() {
        val syncs = listOf(42L to aug(9), 41L to aug(9), 29L to aug(8), 28L to aug(8), 27L to aug(8))
        val missing = BandArchiveRepair.missingSyncIds(
            syncs = syncs,
            archived = setOf(42L, 29L, 27L),
            oldestMonth = 202608,
            monthOf = ::monthOf,
        )
        assertEquals(listOf(41L, 28L), missing)
    }

    /**
     * The window guard, and the reason it exists.
     *
     * A sync older than the oldest file present has its census in a file that is simply not here —
     * pruned, or moved to the backup archive. Treating "no census" as "missing" there would re-emit
     * rows that are perfectly well archived somewhere else, on every single sync, forever.
     */
    @Test
    fun `a sync older than the oldest file present is left alone`() {
        val missing = BandArchiveRepair.missingSyncIds(
            syncs = listOf(30L to aug(8), 3L to jul(20)),
            archived = emptySet(),
            oldestMonth = 202608,
            monthOf = ::monthOf,
        )
        assertEquals("July's sync is not evidence of anything", listOf(30L), missing)
    }

    @Test
    fun `with no file at all nothing is repaired — an empty directory is not a work order`() {
        val missing = BandArchiveRepair.missingSyncIds(
            syncs = listOf(30L to aug(8)),
            archived = emptySet(),
            oldestMonth = null,
            monthOf = ::monthOf,
        )
        assertTrue(missing.isEmpty())
    }

    // --- helpers: epoch millis for a day, and the yyyyMM the engine passes in --------------------
    private fun aug(day: Int): Long = dayMs(2026, 8, day)
    private fun jul(day: Int): Long = dayMs(2026, 7, day)
    private fun dayMs(y: Int, m: Int, d: Int): Long =
        java.time.LocalDate.of(y, m, d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun monthOf(startedAt: Long): Int {
        val date = java.time.Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return date.year * 100 + date.monthValue
    }
}
