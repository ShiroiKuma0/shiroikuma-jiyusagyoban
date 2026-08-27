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

        /**
         * A nap — daytime sleep the band records as its own session.
         *
         * First seen 2026-08-26, 17:04–18:37: one segment of 5580 s carrying code 5, which is
         * exactly the block's declared span. Until it was named, `Stage.of(5)` returned [UNKNOWN],
         * so that hour and a half counted as nothing at all — excluded from [Session.asleepSeconds]
         * and drawn as `?`.
         *
         * The file's own arithmetic agrees it is sleep rather than another kind of awake: with the
         * code named, [Session.alignsWithHeader] passes for that session, because the segment ends
         * precisely on the declared wake time. An awake stage would not be anchored that way.
         *
         * It counts toward the asleep total, and stays its own entry in [Session.totals] rather than
         * being folded into light — a nap is not a sleep depth, and a night has never contained one.
         */
        NAP(5),
        UNKNOWN(-1),
        ;

        /** Time actually spent asleep — every stage but awake, and never an unread one. */
        val isAsleep: Boolean get() = this != AWAKE && this != UNKNOWN

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class Segment(
        val startSeconds: Long,
        val durationSeconds: Int,
        val stage: Stage,
        /** True when a page stamp landed in this pair — see [HuaweiPagedFile]. */
        val mended: Boolean = false,
        /**
         * False when a stamped field could not be put back: the duration is zero and the stage may
         * be [Stage.UNKNOWN]. The segment is kept rather than dropped, because losing one is how a
         * night silently becomes shorter, but nothing should present it as measured.
         */
        val exact: Boolean = true,
    ) {
        val endSeconds: Long get() = startSeconds + durationSeconds
    }

    data class Session(
        /** The band's own bed time — when SLEEP began, not when the segment list begins. */
        val startSeconds: Long,
        /** The band's own wake time — the end of the last non-awake segment. */
        val endSeconds: Long,
        val segments: List<Segment>,
        /**
         * False when a page stamp took one of the header's epochs and the night's own arithmetic
         * could not put it back — the bed or wake time is then the damaged value, off by however
         * much of it the stamp replaced.
         */
        val headerExact: Boolean = true,
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
            get() = segments.lastOrNull { it.stage.isAsleep }?.endSeconds == endSeconds

        /** How many segments a page stamp touched, and how many of those could not be put back. */
        val mendedSegments: Int get() = segments.count { it.mended }
        val inexactSegments: Int get() = segments.count { !it.exact }

        /** Seconds per stage. Nothing is derived or balanced — this is the file, added up. */
        fun totals(): Map<Stage, Int> =
            segments.groupBy { it.stage }.mapValues { (_, v) -> v.sumOf { it.durationSeconds } }

        /** Light + REM + deep. Matches the band's headline "night sleep". */
        val asleepSeconds: Int
            get() = segments.filter { it.stage.isAsleep }.sumOf { it.durationSeconds }
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
    fun parse(bytes: ByteArray): Session? = parseAll(bytes).firstOrNull()

    /**
     * Every night in the file, oldest first.
     *
     * The file is APPEND-ONLY: one block per night, each beginning with its own eight-byte
     * start/end header and its own configuration container. Parsing only the first block — which is
     * what this did until 2026-08-23 — pins the app to the OLDEST night in the file forever. The
     * band went on appending, the file grew from 643 to 1525 bytes, every sync reported "18 sleep
     * segments", and the card showed a two-day-old night with nothing to say it was stale.
     *
     * Blocks are found rather than assumed: a base is an offset whose next eight bytes are a
     * plausible start/end pair no more than a day apart, with the container marker `0x81` thirty-two
     * bytes on. Walking a declared block length instead would put one wrong byte between us and
     * every later night.
     */
    fun parseAll(bytes: ByteArray): List<Session> {
        val bases = ArrayList<Int>()
        var i = 0
        while (i + CONTAINER - RECORD + 1 < bytes.size) {
            val start = be32(bytes, i)
            val end = be32(bytes, i + 4)
            val marker = bytes.getOrNull(i + (CONTAINER - RECORD))?.toInt()?.and(0xFF)

            // A page stamp in one of the two epochs makes it implausible, and the strict test below
            // would then walk straight past the night — losing it whole rather than losing a byte.
            // So a base is also accepted when one epoch is intact and the other is stamped; the
            // relaxation is bounded to roughly one offset in 976 and still demands the 0x81 marker,
            // and [parseBlock] puts the damaged epoch back from the night's own arithmetic.
            val stampedEpoch = HuaweiPagedFile.stampIn(bytes, i, 8) >= 0
            val halfPlausible = stampedEpoch && (start in PLAUSIBLE || end in PLAUSIBLE)

            if (marker == 0x81 && (
                    halfPlausible ||
                        (start in PLAUSIBLE && end in PLAUSIBLE && end > start && end - start <= MAX_SEGMENT)
                    )
            ) {
                bases += i
                i += CONTAINER - RECORD
            } else {
                i++
            }
        }
        return bases.mapIndexedNotNull { n, base ->
            parseBlock(bytes, base, bases.getOrElse(n + 1) { bytes.size })
        }
    }

    private fun parseBlock(bytes: ByteArray, base: Int, limit: Int): Session? {
        if (base + (CONTAINER - RECORD) + 3 > bytes.size) return null

        // Which epoch, if either, the band's page stamp landed in. Both are big-endian, so the stamp
        // may have taken anything from the whole magnitude to the last few minutes of it — the value
        // is not worth testing for plausibility either way, only replacing.
        val stamp = HuaweiPagedFile.stampIn(bytes, base, 8)
        val startStamped = stamp in 0..3
        val endStamped = stamp in 4..7

        val start = be32(bytes, base)
        val end = be32(bytes, base + 4)
        if (!startStamped && start !in PLAUSIBLE) return null
        if (!endStamped && end !in PLAUSIBLE) return null
        if (!startStamped && !endStamped && end <= start) return null
        // Both gone is two unknowns against one equation; nothing here can honestly recover that.
        if (startStamped && endStamped) return null

        // Skip the configuration container whole. Its blocks carry 0x29B9xxxx ids of the same kind
        // the module-feature commands use, and none of them is sleep.
        val container = base + (CONTAINER - RECORD)
        if ((bytes[container].toInt() and 0xFF) != 0x81) return null
        val (length, after) = varInt(bytes, container + 1) ?: return null
        var i = after + length
        if (i > limit) return null

        val raw = ArrayList<Raw>()
        while (i + 4 <= limit) {
            // The stage field may be cut short by a byte: the first night was captured before the
            // client stopped trusting the declared size, and its final pair is seven bytes rather
            // than eight. Reading what is there beats discarding a real segment.
            val stageBytes = minOf(4, limit - (i + 4))
            if (stageBytes <= 0) break

            // Where the band's page stamp falls inside this pair, if it falls in one at all. See
            // [HuaweiPagedFile]: one byte in every 976 is a page number rather than a measurement,
            // and reading it as data is how a night acquires a segment that never happened.
            val stamp = HuaweiPagedFile.stampIn(bytes, i, 4 + stageBytes)

            // Durations are seconds and real ones are minutes — the longest segment yet seen is
            // 5580 s — so a stamp in the top two bytes costs nothing: the value that matters is
            // still there underneath, and reading only the intact bytes recovers it exactly. A
            // stamp in the low two takes the number itself, and only the night's own arithmetic
            // can put it back.
            val duration = when (stamp) {
                0, 1 -> null
                2 -> le(bytes, i, 2).toInt()
                3 -> le(bytes, i, 3).toInt()
                else -> le32(bytes, i).toInt()
            }
            // Stages are 1..5, so every byte above the first is zero and a stamp there is harmless.
            val stage = when (stamp - 4) {
                0 -> null
                in 1..3 -> (bytes[i + 4].toInt() and 0xFF)
                else -> le(bytes, i + 4, stageBytes).toInt()
            }

            // A pair the stamp did not touch is still the alignment check it always was: an
            // implausible duration there means the array has drifted and going on would invent
            // segments. A stamped pair is a known wound, not drift, so it must not stop the night.
            if (stamp < 0 && (duration == null || duration <= 0 || duration > MAX_SEGMENT)) break
            if (duration != null && (duration <= 0 || duration > MAX_SEGMENT) && stamp >= 0) {
                raw += Raw(null, stage, true)
            } else {
                raw += Raw(duration, stage, stamp >= 0)
            }
            i += 4 + stageBytes
        }
        if (raw.isEmpty()) return null

        // The identity has one equation, so it puts back one thing. With an epoch stamped it is
        // spent on that, and a duration lost in the same night stays lost.
        if (!startStamped && !endStamped) recoverDuration(raw, start, end)

        // start − lead + Σ(durations up to the last sleeping segment) = end. Solved for whichever
        // end of it the stamp took.
        val span = if (startStamped || endStamped) sleepSpan(raw) else null
        val sessionStart = if (startStamped && span != null) end - span else start
        val sessionEnd = if (endStamped && span != null) start + span else end
        val headerExact = !(startStamped || endStamped) || span != null
        // A stamp in the top bytes of a big-endian epoch leaves a date decades out. Keeping that
        // would put a night in 2043 on the chart, which is worse than admitting the loss.
        if (!headerExact && (sessionStart !in PLAUSIBLE || sessionEnd !in PLAUSIBLE ||
                sessionEnd <= sessionStart)
        ) {
            return null
        }

        // The header's start/end bracket the SLEEP, not the segment array. On the first captured
        // night the segments run 324 minutes against a declared span of 308, and the excess is
        // exactly a 12-minute awake block at the front plus a 4-minute one at the end.
        //
        // So the array is anchored by its first NON-awake segment, which begins at the declared bed
        // time. Anchoring at the declared start instead — the obvious reading — silently shifts
        // every segment twelve minutes late while leaving all the totals correct, so no summary
        // figure would reveal it and only the hypnogram would be wrong.
        val lead = raw.takeWhile { it.stage != null && Stage.of(it.stage) == Stage.AWAKE }
            .sumOf { it.duration ?: 0 }
        var cursor = sessionStart - lead
        val segments = raw.map { r ->
            val duration = r.duration ?: 0
            Segment(
                startSeconds = cursor,
                durationSeconds = duration,
                stage = if (r.stage == null) Stage.UNKNOWN else Stage.of(r.stage),
                mended = r.stamped,
                exact = r.duration != null && r.stage != null,
            ).also { cursor += duration }
        }
        return Session(sessionStart, sessionEnd, segments, headerExact = headerExact)
    }

    /**
     * `Σ(durations up to the last sleeping segment) − the leading awake run`, or null when a
     * duration is missing and the sum therefore is not a number the file actually states.
     *
     * This is the quantity the header's two epochs differ by, on every night measured so far.
     */
    private fun sleepSpan(raw: List<Raw>): Long? {
        val leadEnd = raw.indexOfFirst { it.stage == null || Stage.of(it.stage) != Stage.AWAKE }
        if (leadEnd < 0) return null
        val last = raw.indexOfLast { it.stage != null && Stage.of(it.stage).isAsleep }
        if (last < 0) return null
        var sum = 0L
        for (k in 0..last) sum += raw[k].duration?.toLong() ?: return null
        val lead = (0 until leadEnd).sumOf { raw[it].duration?.toLong() ?: return null }
        return sum - lead
    }

    /** One pair as it came off the disk, before a stamped field is put back. */
    private data class Raw(val duration: Int?, val stage: Int?, val stamped: Boolean)

    /**
     * Put back the one duration a page stamp took, using the night's own arithmetic.
     *
     * The file offers an identity and it would be a waste not to spend it: anchored on the leading
     * awake run, the LAST non-awake segment ends exactly on the declared wake time — measured at 0 s
     * difference on all eight nights captured so far. So with one duration missing, that identity
     * has one unknown and solves for it exactly. This is reconstruction, not interpolation.
     *
     * It declines in the three cases where the identity says nothing:
     *  - more than one duration missing — two unknowns, one equation;
     *  - the missing one inside the LEADING awake run, where it cancels out (it is subtracted as
     *    part of the anchor and added back as part of the sum), so the arithmetic cannot see it;
     *  - the missing one after the last sleeping segment, which the identity does not reach.
     *
     * A duration left null becomes a zero-length segment flagged inexact rather than a guess.
     */
    private fun recoverDuration(raw: MutableList<Raw>, start: Long, end: Long) {
        val missing = raw.indices.filter { raw[it].duration == null }
        if (missing.size != 1) return
        val j = missing.single()

        val leadEnd = raw.indexOfFirst { it.stage == null || Stage.of(it.stage) != Stage.AWAKE }
        if (leadEnd < 0 || j < leadEnd) return
        val lead = (0 until leadEnd).sumOf { raw[it].duration ?: return }

        val last = raw.indexOfLast { it.stage != null && Stage.of(it.stage).isAsleep }
        if (last < 0 || j > last) return

        var others = 0L
        for (k in 0..last) {
            if (k == j) continue
            others += raw[k].duration?.toLong() ?: return
        }
        val solved = end - start + lead - others
        if (solved <= 0 || solved > MAX_SEGMENT) return
        raw[j] = raw[j].copy(duration = solved.toInt())
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
