package com.opentasker.core.band

import java.io.File
import java.time.LocalDate

/**
 * The append-only archive writer. `java.io` only — no Android, so the path logic is testable.
 *
 * One file per calendar month (`band_2026-08.jsonl`); an explicit full export writes a fresh dated
 * file instead. **Nothing is ever rewritten in place.**
 *
 * Append, deliberately, rather than write-temp-and-rename: rename cannot append, and the archive's
 * whole value is that a sync only ever adds. The cost is that a kill mid-write can leave a torn final
 * line — which is fine, because every line is standalone JSON and a reader drops one it cannot parse.
 */
class BandJsonlWriter(private val directory: File) {

    /**
     * Append [lines] to this month's file, creating it if needed.
     *
     * Returns the file written, or throws — the caller records the reason in the census as
     * `backup:"failed:…"` and leaves the DB alone. The DB is committed FIRST and the file second on
     * purpose: if the file write fails the database is still correct, whereas the reverse order would
     * leave archive lines pointing at rows that do not exist.
     */
    fun appendAll(lines: List<String>, today: LocalDate = LocalDate.now()): File {
        val target = monthlyFile(today)
        if (lines.isEmpty()) return target
        target.parentFile?.mkdirs()
        target.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        return target
    }

    fun monthlyFile(today: LocalDate = LocalDate.now()): File =
        File(directory, "band_%04d-%02d.jsonl".format(today.year, today.monthValue))

    /** A one-off complete export. Stamped to the second, per the fork's full-datetime rule. */
    fun fullExportFile(stamp: String): File = File(directory, "band_full_$stamp.jsonl")

    companion object {
        /**
         * Resolve a configured directory the way the workspace transfer bridge does: an absolute path
         * is used as-is, a bare name lands under [defaultRoot]. Blank falls back to the root itself.
         */
        fun resolveDirectory(configured: String?, defaultRoot: File): File {
            val value = configured?.trim().orEmpty()
            return when {
                value.isEmpty() -> defaultRoot
                value.startsWith('/') -> File(value)
                else -> File(defaultRoot, value)
            }
        }
    }
}
