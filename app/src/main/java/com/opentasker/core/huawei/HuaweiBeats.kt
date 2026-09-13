package com.opentasker.core.huawei

/**
 * Decoding `sequence_data` stream **700021** — the band's own beat-by-beat RR series.
 *
 * ## Why this stream, when `rrisqi_data.bin` was already stored
 *
 * `rrisqi_data.bin` is a *panel*: ten floats summarising a ~56-second window, of which seven are now
 * named (see [HuaweiRri]). This is the series those floats are computed FROM — every interval the
 * band detected, with the signal-quality index the filename has always carried. Three things follow
 * that the panel cannot give:
 *
 *  * **Any HRV statistic, not just the seven Huawei chose.** SDNN and pNN50 are ordinary metrics
 *    with published norms and neither appears in the panel at all.
 *  * **Respiratory rate.** The panel carries HF *power* (f8) and no HF *peak frequency*, so an
 *    elevated f8 cannot be separated into "slower, deeper breathing" and "higher vagal tone". The
 *    peak is recoverable from the intervals and from nothing else — see [HuaweiBeatMetrics].
 *    After skin temperature, which this band has no sensor for, nocturnal respiratory rate is the
 *    best-evidenced day-ahead illness signal in the wearable literature.
 *  * **A check on the panel itself.** f5 was established as RMSSD by computing RMSSD here and
 *    comparing; f3's old reading as "the shortest interval" was disproved the same way.
 *
 * ## The layout
 *
 * | part | shape |
 * |---|---|
 * | file header | `00`, size as BE uint32, stream id as BE uint32 (`0x000AAE75` = 700021) |
 * | record | 44-byte header: start/end epoch as BE uint32 at +0/+4, an **LE echo of the start at +0x24** |
 * | beats | (uint16 LE interval ms, uint16 LE quality), repeated to the next record header |
 *
 * The beat count varies per record, which is exactly why the file reads as strideless until the
 * headers are located. They are **found, not walked** — the same decision [HuaweiSleep.parseAll]
 * records — because one wrong byte would otherwise sit between us and every later record.
 *
 * ## The page stamp, and why it cannot corrupt an interval here
 *
 * The band writes a page index into every 976th byte ([HuaweiPagedFile]). In this stream that is
 * structurally harmless to the measurement: a record header is 44 bytes and a beat is 4, so beats
 * sit at offsets congruent to 1 (mod 4) while stamps sit at 0 — each stamp lands on the same byte of
 * the same field every time, which is a quality field's spare high byte. Measured over a whole
 * 97 789-byte capture: 100 stamps, 88 of them in a quality byte and the rest in record headers.
 *
 * That is a property of the observed alignment rather than a law, so nothing here assumes it. Each
 * beat asks where the stamp fell: in the interval (bytes 0–1) the beat is dropped, in the quality
 * (bytes 2–3) the interval is kept and the quality marked unknown. A silent reliance on the
 * alignment would turn a 40 000 ms interval into a measurement on the day it stops holding.
 *
 * Pure Kotlin, no Android — JVM-testable like the rest of this package.
 */
object HuaweiBeats {

    /** The stream inside `sequence_data` that holds the per-beat series. */
    const val STREAM_ID = 700_021

    /** Bytes from a record header's start to its first beat. */
    const val HEADER = 44

    /** Where the header echoes its own start time, little-endian. The anchor the headers are found by. */
    private const val ECHO = 0x24

    /** Epoch bounds a record timestamp must fall inside to be believed. Same window as [HuaweiSleep]. */
    private val PLAUSIBLE = 1_600_000_000L..2_500_000_000L

    /**
     * The longest a single record may declare, in seconds.
     *
     * Observed records run 60 s, and 93 s later in the file. An hour is far beyond anything seen and
     * still tight enough that a mis-located header fails the test rather than producing a record
     * with thousands of beats in it.
     */
    private const val MAX_SPAN = 3600L

    /**
     * Plausible RR interval bounds, in milliseconds — 20 bpm to 300 bpm.
     *
     * Deliberately wider than any human resting range. This is an alignment check, not a filter: the
     * band already publishes its own opinion of each beat in [Beat.quality], and narrowing this to a
     * physiological window would silently discard the arrhythmic beats that are the most interesting
     * thing the series could ever contain. Quality gating belongs to the caller — see
     * [HuaweiBeatMetrics.usable].
     */
    private val INTERVAL_MS = 200..3000

    /** One detected beat: how long since the previous one, and what the band thought of it. */
    data class Beat(
        val intervalMs: Int,
        /**
         * The band's signal-quality index — observed as exactly {100, 50, 0}, or null when the page
         * stamp took this field. Null is NOT zero: zero is the band saying the beat is unreliable,
         * null is us saying we could not read its opinion.
         */
        val quality: Int?,
    )

    /**
     * One record: a contiguous run of beats under one declared span.
     *
     * [exact] is the file's own arithmetic agreeing with itself — the intervals sum to within
     * [SPAN_TOLERANCE_MS] of the declared span. It held in 266 of 267 records once the headers were
     * read by their LE echo. A record that fails it is kept and flagged rather than dropped: the
     * beats may still be sound and the caller can decide, but nothing should quote a respiratory
     * rate off a record whose own clock disagrees with it.
     */
    data class Record(
        val startSeconds: Long,
        val endSeconds: Long,
        val beats: List<Beat>,
        val exact: Boolean,
    ) {
        val spanSeconds: Long get() = endSeconds - startSeconds

        /** Total time the intervals account for, in milliseconds. */
        val beatSpanMs: Long get() = beats.sumOf { it.intervalMs.toLong() }
    }

    /** How far the summed intervals may fall from the declared span and still count as [Record.exact]. */
    const val SPAN_TOLERANCE_MS = 25_000L

    /**
     * Every record in the file, oldest first.
     *
     * Headers are located by a **triple** test — a plausible BE start, a plausible BE end no more
     * than [MAX_SPAN] later, and the little-endian echo at `+0x24` equalling the start. The echo is
     * what makes this safe: two independent encodings of the same 4-byte number agreeing by chance
     * is not something a run of beat data does, and it is the one copy of the start that a page
     * stamp cannot also have taken.
     */
    fun parse(bytes: ByteArray): List<Record> {
        val bases = ArrayList<Int>()
        var i = 0
        while (i + HEADER <= bytes.size) {
            if (isHeaderAt(bytes, i)) {
                bases += i
                // Past this header, but not past its beats: the next record's base is found by the
                // same test rather than computed from a length this file never states.
                i += HEADER
            } else {
                i++
            }
        }
        return bases.mapIndexed { n, base ->
            readRecord(bytes, base, bases.getOrElse(n + 1) { bytes.size })
        }
    }

    private fun isHeaderAt(bytes: ByteArray, at: Int): Boolean {
        if (at + HEADER > bytes.size) return false
        val start = be32(bytes, at)
        val end = be32(bytes, at + 4)
        if (start !in PLAUSIBLE || end !in PLAUSIBLE) return false
        if (end < start || end - start > MAX_SPAN) return false
        return le32(bytes, at + ECHO) == start
    }

    private fun readRecord(bytes: ByteArray, base: Int, limit: Int): Record {
        val start = be32(bytes, base)
        val end = be32(bytes, base + 4)
        val beats = ArrayList<Beat>()
        var i = base + HEADER
        while (i + 4 <= limit) {
            // Where the band's page stamp falls inside this pair, if it falls in one at all.
            val stamp = HuaweiPagedFile.stampIn(bytes, i, 4)
            when {
                // The interval itself is gone. Nothing can put it back — unlike a sleep segment,
                // there is no per-record identity to solve for one missing beat — so the beat is
                // dropped rather than guessed.
                stamp == 0 || stamp == 1 -> Unit
                else -> {
                    val interval = le16(bytes, i)
                    // An implausible interval on an UNSTAMPED beat means the cursor has drifted out
                    // of the beat grid, and going on would manufacture beats. Stop this record here.
                    if (stamp < 0 && interval !in INTERVAL_MS) break
                    if (interval in INTERVAL_MS) {
                        beats += Beat(interval, if (stamp >= 2) null else le16(bytes, i + 2))
                    }
                }
            }
            i += 4
        }
        val span = (end - start) * 1000L
        val exact = beats.isNotEmpty() &&
            kotlin.math.abs(beats.sumOf { it.intervalMs.toLong() } - span) <= SPAN_TOLERANCE_MS
        return Record(start, end, beats, exact)
    }

    // ---- encoding, for the blob the database stores ------------------------------------------

    /**
     * The beats as the wire pairs the band sent — (uint16 LE interval, uint16 LE quality).
     *
     * Stored rather than the parsed objects because it is the band's own encoding, half the size of
     * any structured alternative, and re-readable by [decode] without a schema. A quality the page
     * stamp took is written as `0xFFFF`, a value the band's own {100, 50, 0} cannot collide with.
     */
    fun encode(beats: List<Beat>): ByteArray {
        val out = ByteArray(beats.size * 4)
        beats.forEachIndexed { n, b ->
            val q = b.quality ?: 0xFFFF
            out[n * 4] = (b.intervalMs and 0xFF).toByte()
            out[n * 4 + 1] = ((b.intervalMs shr 8) and 0xFF).toByte()
            out[n * 4 + 2] = (q and 0xFF).toByte()
            out[n * 4 + 3] = ((q shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** The inverse of [encode]. */
    fun decode(blob: ByteArray): List<Beat> =
        (0 until blob.size / 4).map { n ->
            val q = le16(blob, n * 4 + 2)
            Beat(le16(blob, n * 4), if (q == 0xFFFF) null else q)
        }

    // ---- little helpers ----------------------------------------------------------------------

    private fun be32(b: ByteArray, i: Int): Long {
        if (i + 4 > b.size) return -1
        var v = 0L
        for (n in 0 until 4) v = (v shl 8) or (b[i + n].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, i: Int): Long {
        if (i + 4 > b.size) return -1
        var v = 0L
        for (n in 3 downTo 0) v = (v shl 8) or (b[i + n].toLong() and 0xFF)
        return v
    }

    private fun le16(b: ByteArray, i: Int): Int {
        if (i + 2 > b.size) return 0
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    }
}
