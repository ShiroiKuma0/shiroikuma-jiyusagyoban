package com.opentasker.core.band

import java.io.File

/**
 * The archive's self-healing pass: **every row the DB holds must have a line in the JSONL**.
 *
 * ## Why this exists
 *
 * The archive was written on the success path only. `persist()` commits each stream's rows to the
 * database as that stream lands, and banks the matching JSONL lines; the flush that turns those
 * banked lines into a file write happened after the stream loop, inside the same `try`. So any sync
 * that landed rows and then threw — a GATT exception, a cancelled scope, a kill — left the rows in
 * the database with no line in the file, and the banked lines were dropped on the floor (and leaked,
 * since only the success path cleared the map).
 *
 * That is not a hypothetical. On 白い熊's own archive, syncs **28** (2026-08-08) and **41**
 * (2026-08-09) are absent entirely — no header, no records, no census — while their 27 heart-rate
 * rows sit in the database and show up in the day table. The file was 27 rows short of the database
 * for a day, silently, and the only reason anyone noticed was a hand audit of the two against each
 * other.
 *
 * The flush is now unconditional (see `BandSyncEngine.runLocked`), which stops it happening again.
 * This class is the second line: it **repairs what is already missing**, and it keeps repairing, so
 * that a failure mode nobody has thought of yet cannot quietly cost the backup a day's data either.
 * A backup that is silently incomplete is worse than no backup, because it is trusted.
 *
 * ## How a sync is known to be archived
 *
 * By its **census line**. A sync writes one header, N record lines and one census, and the census is
 * written LAST — so its presence means every line before it landed. A repair block follows the same
 * rule: records first, marker last, and the marker names the sync ids it covered. Ids seen in either
 * shape are accounted for; everything else in the window is missing and gets re-emitted from the DB.
 *
 * ## The window is bounded by the files themselves
 *
 * Only syncs at or after the **oldest monthly file present** are considered. Anything older has its
 * census in a file that is not here — pruned, moved to the backup archive, never written on an older
 * build — and treating "no census" as "missing" there would re-emit rows that are perfectly well
 * archived somewhere else. With no monthly file at all this does nothing: an empty directory is not
 * evidence that thousands of rows need writing, and rebuilding an archive from scratch is a decision
 * for 白い熊, not something a background sync should do on its own.
 *
 * Pure `java.io` and pure Kotlin, so all of it is JVM-testable.
 */
object BandArchiveRepair {

    /** `band_2026-08.jsonl` — the per-month files a sync appends to. */
    private val MONTHLY = Regex("""^band_(\d{4})-(\d{2})\.jsonl$""")

    /**
     * How many syncs back to look. Generous: at 6–8 syncs a day this is roughly a month, and the
     * only cost of a candidate that turns out to be archived already is a set lookup.
     */
    const val LOOKBACK_SYNCS = 300

    /** SQLite's variable limit is 999; chunk well under it. */
    const val ID_CHUNK = 200

    /**
     * The monthly files, oldest first.
     *
     * `band_full_*.jsonl` — the one-off complete exports — are deliberately excluded. They are
     * snapshots taken on demand and are not where a sync appends, so counting their contents as
     * "archived" would let a genuinely missing sync go unrepaired.
     */
    fun monthlyFiles(directory: File): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && MONTHLY.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** `yyyyMM` of the oldest monthly file, or null when there is none. */
    fun oldestMonth(files: List<File>): Int? =
        files.firstOrNull()?.let { file ->
            MONTHLY.find(file.name)?.destructured?.let { (y, m) -> y.toInt() * 100 + m.toInt() }
        }

    /**
     * Every sync id the archive already accounts for.
     *
     * Scanned with a `startsWith` fast path rather than by parsing each line: the file is tens of
     * thousands of sample lines and a few dozen brackets, and JSON-decoding all of them to find the
     * brackets would cost far more than the whole repair. A torn line simply fails to match and is
     * skipped, exactly as every other reader of this archive treats one.
     */
    fun archivedSyncIds(files: List<File>): Set<Long> {
        val out = HashSet<Long>()
        for (file in files) {
            runCatching {
                file.forEachLine { line ->
                    when {
                        line.startsWith(CENSUS_PREFIX) -> idOf(line)?.let { out += it }
                        line.startsWith(REPAIR_PREFIX) -> out += idsOf(line)
                    }
                }
            }
        }
        return out
    }

    private const val CENSUS_PREFIX = """{"t":"census","""
    private const val REPAIR_PREFIX = """{"t":"repair","""

    /** `…"id":42,…` → 42. Deliberately not a JSON parse — see [archivedSyncIds]. */
    private fun idOf(line: String): Long? {
        val at = line.indexOf(""""id":""")
        if (at < 0) return null
        return line.drop(at + 5).takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** `…"ids":[28,41],…` → {28, 41}. */
    private fun idsOf(line: String): List<Long> {
        val at = line.indexOf(""""ids":[""")
        if (at < 0) return emptyList()
        val end = line.indexOf(']', at)
        if (end < 0) return emptyList()
        return line.substring(at + 7, end)
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
    }

    /**
     * The syncs whose rows are in the database but not in the archive, newest first.
     *
     * [syncs] is `(id, startedAtMs)` newest-first, straight off `BandSyncDao.recent`. [monthOf] maps
     * a start time to `yyyyMM` in the device's zone — passed in rather than computed here so this
     * stays free of `ZoneId` and testable with a plain lambda.
     */
    fun missingSyncIds(
        syncs: List<Pair<Long, Long>>,
        archived: Set<Long>,
        oldestMonth: Int?,
        monthOf: (Long) -> Int,
    ): List<Long> {
        if (oldestMonth == null) return emptyList()
        return syncs
            .filter { (id, startedAt) -> id !in archived && monthOf(startedAt) >= oldestMonth }
            .map { it.first }
    }
}
