package com.opentasker.core.huawei

/**
 * Decoding `rrisqi_data.bin` — the band's beat-to-beat summaries.
 *
 * This is the file the whole Huawei experiment was for. The Hume band reported a "HRV" number that
 * turned out to be a device-state index, and never sent beat-to-beat data at all; this band records
 * a genuine ~56-second analysis window every few minutes.
 *
 * ## What is known, and what is honestly not
 *
 * Each record holds **ten IEEE-754 big-endian float32 fields**. Two are pinned against Huawei
 * Health's own displayed lists, on 白い熊's own wrist:
 *
 *  * **[validIntervals] (field 1)** — how many intervals the window accepted. Health publishes a
 *    window only when this is roughly 17 or more: a clean 9-for-9 split between the three records it
 *    listed (32, 19, 20) and the six it omitted (16, 14, 12, 9, 9, 7).
 *  * **[meanRrMs] (field 6)** — the mean RR interval in milliseconds, quantised to 20 ms.
 *    `60000 / meanRrMs` reproduces Health's heart rate across 8 overlapping points at RMSE 2.15 bpm,
 *    and it is the ONLY fit under 6 bpm across all 66 byte positions and both endiannesses.
 *
 *  * **[rmssdMs] (field 5)** — RMSSD, the root mean square of successive differences, in
 *    milliseconds. Established 2026-08-25 against the band's OWN per-beat series: `sequence_data`
 *    700021 turned out to be (uint16 LE interval ms, uint16 LE quality) pairs, so RMSSD can be
 *    computed here and compared. In 69 of 124 windows where both files provably describe the same
 *    episode it agrees to within 1 %, at a ratio of 0.9927 — a scale of one, not an arbitrary
 *    multiple. The other 55 are windows the two files do not share; no beat-selection rule changes
 *    the split, and a shuffled null sits at 57 % median error.
 *
 * Field 3 is the other 20 ms-quantised value. It was read as the shortest interval in the window;
 * the per-beat series says otherwise — the minimum matches it in 0 of 124 windows, while the RANGE
 * (max − min), rounded to the 20 ms grid, matches exactly in 76 of 142. Carried in [raw] still,
 * because half the windows is where the evidence stops.
 *
 * **Health's own HRV figure is NOT in this file.** Of three overlapping entries, one (35 ms) appears
 * nowhere in its record at any offset or encoding, so Health derives it from data it holds and we do
 * not. Two other fields each matched one of the remaining two values, which is what coincidence
 * looks like — so nothing here is labelled "HRV". When a field earns that name it will be because a
 * measurement said so.
 */
object HuaweiRri {

    /** One ~56-second analysis window. */
    data class Window(
        val startSeconds: Long,
        val endSeconds: Long,
        /** Field 1 — intervals the band accepted in this window. */
        val validIntervals: Int,
        /** Field 6 — mean RR interval, milliseconds, in 20 ms steps. */
        val meanRrMs: Double,
        /** Every field by its position, 1-based, including the two named above. Nothing is dropped. */
        val raw: Map<Int, Double>,
    ) {
        /** Heart rate implied by the mean interval, or null when the band recorded none. */
        val heartRate: Double? get() = if (meanRrMs > 0) 60_000.0 / meanRrMs else null

        /**
         * RMSSD in milliseconds — a real, standard HRV measure, computed by the band itself.
         *
         * The storage key stays `rri_f5` on purpose: rows already written under it would be orphaned
         * by a rename, and that is a migration rather than a doc change. The name lives here, where
         * it costs nothing to be right about.
         */
        val rmssdMs: Double get() = raw[5] ?: 0.0

        /**
         * Would Huawei Health publish this window?
         *
         * Worth honouring rather than second-guessing: a window the vendor's own app discards is one
         * the band itself considers too sparse to mean anything, and charting it would put noise on
         * screen that Huawei declines to show.
         */
        val publishable: Boolean get() = validIntervals >= MIN_VALID_INTERVALS
    }

    /** The threshold Health appears to apply; measured, not documented. */
    const val MIN_VALID_INTERVALS = 17

    private const val HEADER = 0x30
    private const val RECORD = 0x42
    private const val FIRST_FLOAT = 19
    private const val FIELDS = 10

    private val PLAUSIBLE = 1_600_000_000L..2_500_000_000L

    /**
     * Parse the file, or return an empty list when it is not this shape.
     *
     * Returns what it can rather than throwing: the file is a rolling buffer and a partially
     * captured tail is normal, whereas losing every window because the last one is short is not.
     */
    fun parse(bytes: ByteArray): List<Window> {
        if (bytes.size < HEADER + RECORD) return emptyList()
        val out = ArrayList<Window>()
        var offset = HEADER
        while (offset + RECORD <= bytes.size) {
            val r = bytes.copyOfRange(offset, offset + RECORD)
            offset += RECORD
            // Byte 0 of a record is a flag; the timestamps start at 1. A record whose stamps are not
            // plausible epochs means the stride has drifted, and continuing would invent data.
            val start = be32(r, 1)
            val end = be32(r, 5)
            if (start !in PLAUSIBLE || end !in PLAUSIBLE || end < start) break
            val fields = (0 until FIELDS).associate { i ->
                (i + 1) to float32(r, FIRST_FLOAT + i * 4)
            }
            out += Window(
                startSeconds = start,
                endSeconds = end,
                validIntervals = fields.getValue(1).toInt(),
                meanRrMs = fields.getValue(6),
                raw = fields,
            )
        }
        return out
    }

    private fun be32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun float32(b: ByteArray, i: Int): Double {
        var bits = 0
        for (k in 0 until 4) bits = (bits shl 8) or (b[i + k].toInt() and 0xFF)
        return Float.fromBits(bits).toDouble()
    }
}
