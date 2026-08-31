package com.opentasker.core.huawei

/**
 * The page index the band writes into every 976th byte of some of its files.
 *
 * ## What it is
 *
 * In a GPS track and in every `sequence_data` stream, the byte at each offset that is a multiple of
 * 976 holds that page's own number: byte 976 is `1`, byte 1952 is `2`, byte 24400 is `25`. Measured
 * 2026-08-27 across three walks and five sleep captures — 172 boundaries, no exception.
 *
 * ## It overwrites a byte rather than inserting one
 *
 * Tested rather than assumed, because the whole repair turns on it: every way of stripping those
 * bytes back out turns a track to noise (7.5 % of records at the file's own 1 s cadence, 64 % of
 * steps under 5 m), while reading the file exactly as it arrived gives 99.6 % and 99.0 %. The record
 * grid is correctly aligned around them and one byte inside it is simply gone.
 *
 * ## It is the band's, not ours
 *
 * `rrisqi_data.bin` comes through the identical client, session and code path and carries none of
 * them. If our reassembly wrote them, it would have written them there too. So this is how the band
 * stores those files — 976-byte flash pages, each stamped with its own index — and Huawei Health
 * must skip them exactly as this does. It also explains the GPS header that is "33 bytes, not the 32
 * every published description gives": byte 0 is page 0's index, landing on a header byte that is
 * zero anyway.
 *
 * ## Why each site is verified rather than assumed
 *
 * The stamp is a property of the stored file, but a transfer that needs more than one window can
 * lose the alignment — visible in a 421 KB `sequence_data` capture whose indices run true to page
 * 215 and then stop. A verified site cannot give a false negative (at a real page boundary the byte
 * IS the index, so it always matches), and declining to repair an unverified one is exactly right:
 * there is no stamp there to undo.
 */
object HuaweiPagedFile {

    /** The band's page size. Also the chunk size its file service serves — see `HuaweiFileClient`. */
    const val PAGE = 976

    /** The index byte an offset should carry, if it is a page boundary at all. */
    fun expectedAt(offset: Int): Int = (offset / PAGE) and 0xFF

    /** True when [offset] is a page boundary AND carries the stamp it should. */
    fun stampedAt(bytes: ByteArray, offset: Int): Boolean =
        offset % PAGE == 0 && offset < bytes.size &&
            (bytes[offset].toInt() and 0xFF) == expectedAt(offset)

    /**
     * Where the page stamp falls inside the [length] bytes at [offset], or -1 when none does.
     *
     * At most one can: pages are 976 bytes apart and the records asking this are 8 to 19 long.
     */
    fun stampIn(bytes: ByteArray, offset: Int, length: Int): Int {
        val next = ((offset + PAGE - 1) / PAGE) * PAGE
        if (next >= offset + length) return -1
        return if (stampedAt(bytes, next)) next - offset else -1
    }

    /**
     * How many page stamps a whole file carries, and whether every boundary in it is stamped.
     *
     * For a report rather than a decision: a file that is only partly stamped is a transfer that
     * needed more than one window, and saying so is more useful than a boolean.
     */
    fun audit(bytes: ByteArray): Audit {
        var boundaries = 0
        var stamped = 0
        var at = PAGE
        while (at < bytes.size) {
            boundaries++
            if (stampedAt(bytes, at)) stamped++
            at += PAGE
        }
        return Audit(boundaries, stamped)
    }

    data class Audit(val boundaries: Int, val stamped: Int) {
        val whole: Boolean get() = boundaries == stamped
    }
}
