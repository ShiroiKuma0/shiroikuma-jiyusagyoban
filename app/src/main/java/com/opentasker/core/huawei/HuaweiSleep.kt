package com.opentasker.core.huawei

/**
 * Decoding a night out of `sequence_data` stream **700013**.
 *
 * ## How this was established
 *
 * Not inferred — checked against the band's own Sleep screen for the night of 2026-08-21. The file
 * carries no totals at all: 290, 308, 83, 157 and 50 appear nowhere in its 642 bytes, so Huawei
 * Health computes the summary itself from the segment list. Summing the segments by stage gives
 * light 157 min, REM 50 min and deep 83 min against a screen reading 2 h 37 min, 50 min and
 * 1 h 23 min — three exact matches, and their sum is the headline 4 h 50 min to the minute.
 *
 * The stage numbering follows from those matches rather than from any convention, which matters:
 * the obvious guess (1 = deep, ascending to lighter) is wrong, and would have swapped deep with
 * light in every chart while still producing a plausible-looking night.
 *
 * ## Layout
 *
 * ```
 * 0x00        00
 * 0x01..0x04  size, uint32 BE   (a LOWER bound — the band sends one byte more; see HuaweiFileClient)
 * 0x05..0x08  stream id, uint32 BE
 * 0x09..0x20  flags and padding
 * 0x21..0x24  session start, epoch seconds, uint32 BE
 * 0x25..0x28  session end,   epoch seconds, uint32 BE
 * 0x29..0x40  flags
 * 0x41        TLV container, tag 0x81 + VarInt length — configuration blocks carrying 0x29B9xxxx
 *             ids, NOT sleep. Skipped wholesale.
 * then        the segments: pairs of uint32 LITTLE-endian — duration in seconds, then stage.
 * ```
 *
 * The endianness genuinely flips mid-file: every header field is big-endian and every segment field
 * is little-endian. Reading the segments big-endian yields durations in the hundreds of millions,
 * which is at least loud rather than subtly wrong.
 */
object HuaweiSleep {

    enum class Stage(val code: Int) {
        LIGHT(1),
        REM(2),
        DEEP(3),
        /**
         * Awake. Its total does not reconcile with the screen's arithmetic: 30 minutes here against
         * the 18 that 308 minutes of span minus 290 asleep implies. The difference is exactly the
         * leading 12-minute block, which suggests the band excludes awake time before sleep onset
         * from the span — but that is arithmetic that happens to work on one night, so nothing here
         * depends on it and no total is adjusted to fit.
         */
        AWAKE(4),
        UNKNOWN(-1),
        ;

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class Segment(
        val startSeconds: Long,
        val durationSeconds: Int,
        val stage: Stage,
    ) {
        val endSeconds: Long get() = startSeconds + durationSeconds
    }

    data class Session(
        /** The band's own bed time — when SLEEP began, not when the segment list begins. */
        val startSeconds: Long,
        /** The band's own wake time — the end of the last non-awake segment. */
        val endSeconds: Long,
        val segments: List<Segment>,
    ) {
        /**
         * Does the segment list line up with the header the way the first night did?
         *
         * The file offers its own check and it would be a waste not to take it: anchoring is
         * derived from the leading awake run, so the LAST non-awake segment ending exactly on the
         * declared wake time is an independent confirmation that the whole array is aligned. A
         * night where this is false is not refused — it is still mostly readable — but nothing
         * should present it as exact.
         */
        val alignsWithHeader: Boolean
            get() = segments.lastOrNull { it.stage != Stage.AWAKE && it.stage != Stage.UNKNOWN }
                ?.endSeconds == endSeconds

        /** Seconds per stage. Nothing is derived or balanced — this is the file, added up. */
        fun totals(): Map<Stage, Int> =
            segments.groupBy { it.stage }.mapValues { (_, v) -> v.sumOf { it.durationSeconds } }

        /** Light + REM + deep. Matches the band's headline "night sleep". */
        val asleepSeconds: Int
            get() = segments.filter { it.stage != Stage.AWAKE && it.stage != Stage.UNKNOWN }
                .sumOf { it.durationSeconds }
    }

    /** Where the segment array begins, and where the config container starts. */
    private const val RECORD = 0x21
    private const val CONTAINER = 0x41

    /** Epoch bounds a session timestamp must fall inside to be believed. */
    private val PLAUSIBLE = 1_600_000_000L..2_500_000_000L

    /**
     * Parse one night, or null when the bytes are not that shape.
     *
     * Returns null rather than a best effort. A half-understood sleep file decodes into a night that
     * never happened, and nothing downstream could tell it from a real one — so a structure we do
     * not recognise has to stop here, where the evidence still exists, rather than three layers
     * later in a chart.
     */
    fun parse(bytes: ByteArray): Session? {
        if (bytes.size < CONTAINER + 3) return null

        val start = be32(bytes, RECORD)
        val end = be32(bytes, RECORD + 4)
        if (start !in PLAUSIBLE || end !in PLAUSIBLE || end <= start) return null

        // Skip the configuration container whole. Its blocks carry 0x29B9xxxx ids of the same kind
        // the module-feature commands use, and none of them is sleep.
        if ((bytes[CONTAINER].toInt() and 0xFF) != 0x81) return null
        val (length, after) = varInt(bytes, CONTAINER + 1) ?: return null
        var i = after + length
        if (i > bytes.size) return null

        val raw = ArrayList<Pair<Int, Int>>()
        while (i + 4 <= bytes.size) {
            val duration = le32(bytes, i)
            // The stage field may be cut short by a byte: the first night was captured before the
            // client stopped trusting the declared size, and its final pair is seven bytes rather
            // than eight. Reading what is there beats discarding a real segment.
            val stageBytes = minOf(4, bytes.size - (i + 4))
            if (stageBytes <= 0) break
            val stage = le(bytes, i + 4, stageBytes)
            if (duration <= 0 || duration > MAX_SEGMENT) break
            raw += duration.toInt() to stage.toInt()
            i += 4 + stageBytes
        }
        if (raw.isEmpty()) return null

        // The header's start/end bracket the SLEEP, not the segment array. On the first captured
        // night the segments run 324 minutes against a declared span of 308, and the excess is
        // exactly a 12-minute awake block at the front plus a 4-minute one at the end.
        //
        // So the array is anchored by its first NON-awake segment, which begins at the declared bed
        // time. Anchoring at the declared start instead — the obvious reading — silently shifts
        // every segment twelve minutes late while leaving all the totals correct, so no summary
        // figure would reveal it and only the hypnogram would be wrong.
        val lead = raw.takeWhile { Stage.of(it.second) == Stage.AWAKE }.sumOf { it.first }
        var cursor = start - lead
        val segments = raw.map { (duration, stage) ->
            Segment(cursor, duration, Stage.of(stage)).also { cursor += duration }
        }
        return Session(start, end, segments)
    }

    /** A single stage cannot plausibly run longer than a day; anything past it is misalignment. */
    private const val MAX_SEGMENT = 86_400L

    private fun be32(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, i: Int): Long = le(b, i, 4)

    private fun le(b: ByteArray, i: Int, n: Int): Long {
        var v = 0L
        for (k in n - 1 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        return v
    }

    /** Huawei's VarInt: seven bits per byte, high bit continues. */
    private fun varInt(b: ByteArray, start: Int): Pair<Int, Int>? {
        var i = start
        var v = 0
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            v = (v shl 7) or (x and 0x7F)
            i++
            if (x and 0x80 == 0) return v to i
            if (i - start > 4) return null
        }
        return null
    }
}
