package com.opentasker.ui.charts

/**
 * Turning the band's sleep segments into something drawable.
 *
 * The band reports sleep as **segments, not nights**: one record covers at most 120 minutes, so a
 * night arrives as several records that have to be stitched. Modelling it as "one row per night" is
 * the mistake that hurts forever, which is why the database stores segments and the stitching lives
 * here, in a pure file that can be tested without a device.
 *
 * Stage codes are the band's RAW bytes — 1 deep, 2 light, 3 REM, 5 awake. The vendor plugin re-codes
 * them to 1=deep 2=light 3=awake 4=REM before its own layer sees them, and mixing the two schemes
 * silently swaps REM and awake. Code 4 has never been observed in 2 970 stage-minutes across six
 * nights; it is carried as unknown rather than assumed away.
 */

/** A contiguous stretch of one stage. [endMs] is exclusive. */
data class SleepRun(val startMs: Long, val endMs: Long, val code: Char) {
    val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
}

/** One night, stitched from however many segments the band split it into. */
data class SleepSession(
    val startMs: Long,
    val endMs: Long,
    val runs: List<SleepRun>,
) {
    val totalMinutes: Int get() = runs.sumOf { it.minutes }
    fun minutesOf(code: Char): Int = runs.filter { it.code == code }.sumOf { it.minutes }
    val deep: Int get() = minutesOf('1')
    val light: Int get() = minutesOf('2')
    val rem: Int get() = minutesOf('3')
    val awake: Int get() = minutesOf('5')

    /**
     * Deep + REM as a share of the session — the "restorative" fraction the index uses.
     *
     * Awake minutes stay in the denominator on purpose: an hour lying awake in the middle of the
     * night genuinely does make the night less restorative, and excluding it would flatter exactly
     * the nights worth noticing.
     */
    val deepRemShare: Double? get() = totalMinutes.takeIf { it > 0 }?.let { (deep + rem).toDouble() / it }
}

/** One stored segment, already converted to epoch millis by the caller. */
data class SleepSegmentInput(val startMs: Long, val minutes: Int, val stages: String)

object SleepShape {

    const val MINUTE_MS = 60_000L

    /**
     * Segments closer than this are the same night.
     *
     * Ninety minutes, because the gap distribution is bimodal with nothing in between. Across the
     * 117 segments of 2026-08, every gap that sits **inside** a night is 0–19 minutes save one, and
     * the smallest gap that genuinely separates two sleeps is **197 minutes** (an evening nap on
     * 08-02, ending 19:26, before a night starting 22:43). The band simply stops emitting stages
     * while you are out of bed, so a trip to the bathroom punches a hole of exactly this kind.
     *
     * The old value was 20 minutes, set when 19 was the largest hole on record — which made it a
     * threshold with no margin at all. On 2026-08-20 the band left a **29-minute** hole at 04:00,
     * so a 7h05m night was cut in two and the screen reported its 1h39m tail as the night (白い熊).
     * That fragment fed the headline, the stage table, the 健康指数 and recovery alike, since all of
     * them read the latest session.
     *
     * 90 minutes is three times the worst hole ever observed and less than half the shortest real
     * separation, so it sits in the middle of an empty band rather than on the edge of a cluster.
     * Erring towards stitching is also the safer side empirically: a fractured night has happened,
     * a nap within 90 minutes of a night never has.
     */
    const val STITCH_TOLERANCE_MS = 90 * MINUTE_MS

    /**
     * Collapse per-minute stage codes into runs.
     *
     * A run per minute would be 500-odd marks a night, all of them abutting; runs of equal stages are
     * both the honest shape (the stage did not change) and two orders of magnitude cheaper to draw.
     */
    fun runs(segment: SleepSegmentInput): List<SleepRun> {
        if (segment.stages.isEmpty()) return emptyList()
        val out = ArrayList<SleepRun>()
        var runStart = 0
        for (i in 1..segment.stages.length) {
            val ended = i == segment.stages.length || segment.stages[i] != segment.stages[runStart]
            if (ended) {
                out += SleepRun(
                    startMs = segment.startMs + runStart * MINUTE_MS,
                    endMs = segment.startMs + i * MINUTE_MS,
                    code = segment.stages[runStart],
                )
                runStart = i
            }
        }
        return out
    }

    /**
     * Stitch segments into sessions.
     *
     * A gap longer than [STITCH_TOLERANCE_MS] starts a new session — that is a nap and a night, not
     * one very long sleep. Segments arrive in any order and are sorted here rather than trusted.
     */
    fun sessions(segments: List<SleepSegmentInput>): List<SleepSession> {
        if (segments.isEmpty()) return emptyList()
        val sorted = segments.sortedBy { it.startMs }
        val out = ArrayList<SleepSession>()
        var current = ArrayList<SleepRun>()
        var start = sorted.first().startMs
        var end = start

        for (seg in sorted) {
            if (current.isNotEmpty() && seg.startMs - end > STITCH_TOLERANCE_MS) {
                out += SleepSession(start, end, current)
                current = ArrayList()
                start = seg.startMs
            }
            val r = runs(seg)
            if (r.isEmpty()) continue
            if (current.isEmpty()) start = r.first().startMs
            current += r
            end = maxOf(end, r.last().endMs)
        }
        if (current.isNotEmpty()) out += SleepSession(start, end, current)
        return out
    }

    /** The stages, in the order their rows stack on the hypnogram: deep at the bottom. */
    val ROWS: List<Char> = listOf('5', '3', '2', '1')

    fun rowOf(code: Char): Int = ROWS.indexOf(code).takeIf { it >= 0 } ?: ROWS.size

    fun labelOf(code: Char): Loc = when (code) {
        '1' -> Loc("Deep", "深い")
        '2' -> Loc("Light", "浅い")
        '3' -> Loc("REM", "REM")
        '5' -> Loc("Awake", "覚醒")
        else -> Loc("Unknown", "不明")
    }
}
