package com.opentasker.ui.charts

import java.time.Instant
import java.time.ZoneId

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
    /** Asleep with no stage — an authored stretch the watch never scored; see [SleepShape.withNightEdges]. */
    val unstaged: Int get() = minutesOf(SleepShape.UNSTAGED)

    /**
     * Deep + REM as a share of the session — the "restorative" fraction the index uses.
     *
     * Awake minutes stay in the denominator on purpose: an hour lying awake in the middle of the
     * night genuinely does make the night less restorative, and excluding it would flatter exactly
     * the nights worth noticing.
     */
    val deepRemShare: Double? get() = (totalMinutes - unstaged).takeIf { it > 0 }?.let { (deep + rem).toDouble() / it }
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

    /**
     * The nocturnal window, tested against a session's **midpoint**: 21:00 to 09:00.
     *
     * A night is defined by WHEN it happened, not by how long it lasted. That is the honest
     * definition — a half-hour doze at 03:00 is part of the night, and three hours on the sofa at
     * 16:00 is a nap — and on 白い熊's own data it is also the one that works. Over the 24 sessions
     * of 2026-08 the two populations do not overlap anywhere near this window:
     *
     * | | midpoints |
     * | --- | --- |
     * | the 19 nights | 01:27 – 04:50 |
     * | the 5 naps | 11:32, 15:53, 16:23, 18:11, 19:11 |
     *
     * The nearest nap sits 1 h 49 m outside the window; the nearest night, 4 h 10 m inside it.
     *
     * **Duration was tried and is wrong.** [RecoverySource.MIN_NIGHT_MINUTES] is four hours, and on
     * 2026-08-04 白い熊 slept 03:21–06:19 — 178 minutes, a short broken night with a midpoint of
     * 04:50. A length test files that as a nap. This one does not. (The four-hour floor is right
     * where it stands, for a different reason: the recovery estimator needs a session long enough to
     * fill its "onset + 30 min, then four hours" window, and 178 minutes cannot.)
     */
    const val NIGHT_FROM_MINUTE = 21 * 60
    const val NIGHT_TO_MINUTE = 9 * 60

    /** The instant halfway through a session — what [isNightMidpoint] is asked about. */
    fun midpointMs(session: SleepSession): Long =
        session.startMs + (session.endMs - session.startMs) / 2

    /** The rule itself, as arithmetic: no clock, no zone, trivially testable. */
    fun isNightMidpoint(minuteOfDay: Int): Boolean =
        minuteOfDay >= NIGHT_FROM_MINUTE || minuteOfDay < NIGHT_TO_MINUTE

    fun minuteOfDay(ms: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    fun isNight(session: SleepSession, zone: ZoneId): Boolean =
        isNightMidpoint(minuteOfDay(midpointMs(session), zone))

    /**
     * The night the screen should be talking about: the most recent one, naps ignored.
     *
     * Null when nothing on record is a night. That is deliberate — falling back to "the most recent
     * session" would put an afternoon nap in the headline in exactly the case this exists to prevent
     * (白い熊, 2026-08-20). A dash and a day table full of dated rows underneath is the truthful
     * answer to "no night has been recorded".
     */
    fun latestNight(sessions: List<SleepSession>, zone: ZoneId): SleepSession? =
        sessions.filter { isNight(it, zone) }.maxByOrNull { it.endMs }

    /**
     * How whole a night was: minutes awake between falling asleep and the final waking (WASO, the
     * standard measure of a broken night), and — when the night was broken by a long spell up — which
     * part of it was the main sleep and which the catch-up after.
     *
     * 白い熊, 2026-11-08: “sleep was broken due to pain, so I had to get up and then catch up on some
     * sleep — this should be taken as bad.” Joining the two sleeps into one night (HuaweiNights) is
     * right for the record, but graded on the total the catch-up made the night look long and good.
     */
    data class Continuity(
        /** Awake minutes after sleep onset and before the final waking. */
        val wasoMinutes: Double,
        /** Asleep minutes in the longest stretch between spells up of [BREAK_MINUTES] or more. */
        val mainSleepMinutes: Double,
        /** Asleep minutes outside that stretch — sleep caught up on after getting up. */
        val catchUpMinutes: Double,
        /** The longest spell awake inside the night, or null when there was none. */
        val longestWakeFromMs: Long?,
        val longestWakeToMs: Long?,
    )

    /** A spell awake at least this long splits a night into a main sleep and a catch-up. */
    const val BREAK_MINUTES = 60

    fun continuity(session: SleepSession): Continuity? {
        val runs = session.runs.sortedBy { it.startMs }
        val asleep = runs.filter { it.code != '5' }
        if (asleep.isEmpty()) return null
        val onset = asleep.first().startMs
        val final = asleep.maxOf { it.endMs }
        val wakes = runs.filter { it.code == '5' && it.startMs >= onset && it.endMs <= final }
        val waso = wakes.sumOf { it.minutes }.toDouble()
        val longest = wakes.maxByOrNull { it.endMs - it.startMs }
        // Split at every long spell up; the block with the most sleep is the main one.
        val cuts = wakes.filter { it.minutes >= BREAK_MINUTES }
        val blocks = ArrayList<Double>()
        var from = Long.MIN_VALUE
        for (cut in cuts + listOf(SleepRun(Long.MAX_VALUE, Long.MAX_VALUE, '5'))) {
            blocks += asleep.filter { it.startMs >= from && it.endMs <= cut.startMs }.sumOf { it.minutes }.toDouble()
            from = cut.endMs
        }
        val total = asleep.sumOf { it.minutes }.toDouble()
        val main = blocks.maxOrNull() ?: total
        return Continuity(waso, main, total - main, longest?.startMs, longest?.endMs)
    }

    /** WASO as a 1–5 step: under 20 min, 20–40, 40–60, 60–90, over 90 (白い熊's steps, 2026-11-08). */
    fun wasoStep(minutes: Double): Int = when {
        minutes < 20 -> 1
        minutes < 40 -> 2
        minutes < 60 -> 3
        minutes <= 90 -> 4
        else -> 5
    }

    /** The stages, in the order their rows stack on the hypnogram: deep at the bottom. */
    val ROWS: List<Char> = listOf('5', '3', '2', '1')

    fun rowOf(code: Char): Int = ROWS.indexOf(code).takeIf { it >= 0 } ?: ROWS.size

    fun labelOf(code: Char): Loc = when (code) {
        '1' -> Loc("Deep", "深い")
        '2' -> Loc("Light", "浅い")
        '3' -> Loc("REM", "REM")
        '5' -> Loc("Awake", "覚醒")
        UNSTAGED -> Loc("Asleep, unstaged", "睡眠（段階なし）")
        else -> Loc("Unknown", "不明")
    }

    /**
     * Asleep, stage unknown. Counted as sleep, never as a stage: it is in the night's length and
     * its asleep minutes, and out of every stage figure and the deep+REM share's denominator — a
     * share diluted by minutes nobody staged would be a share the watch never measured. Not drawn on
     * the hypnogram, which has no row for "no stage".
     */
    const val UNSTAGED = '0'

    /** Only the bedtimes — [withNightEdges] with no wake times. */
    fun withNightStarts(
        sessions: List<SleepSession>,
        starts: Map<Long, Long>,
        morningOf: (Long) -> Long,
    ): List<SleepSession> = withNightEdges(sessions, starts, emptyMap(), morningOf)

    /**
     * Apply 白い熊's own bedtimes ([com.opentasker.core.band.NightStarts]) and wake times
     * ([com.opentasker.core.band.NightEnds]) to the stitched nights.
     *
     * Both map the morning the WATCH ended a night on (`yyyyMMdd`, via [morningOf]) to an instant; the
     * key is read once, from the watch's own end, so a corrected end past midnight keeps its row.
     *
     * A bedtime earlier than the watch's start adds the gap as [UNSTAGED] sleep; a later one cuts off
     * the runs before it. One not before the night's end, or more than 18 hours before it, is ignored.
     * Then the wake time, against the start just settled: later than the watch's end adds the gap as
     * [UNSTAGED] sleep, earlier trims the back. One not after the start, or more than 18 hours after
     * it, is ignored — neither may stretch a night into another day.
     */
    fun withNightEdges(
        sessions: List<SleepSession>,
        starts: Map<Long, Long>,
        ends: Map<Long, Long>,
        morningOf: (Long) -> Long,
    ): List<SleepSession> {
        if (starts.isEmpty() && ends.isEmpty()) return sessions
        return sessions.map { s ->
            val key = morningOf(s.endMs)
            val started = starts[key]?.let { withStart(s, it) } ?: s
            ends[key]?.let { withEnd(started, it) } ?: started
        }
    }

    private fun withStart(s: SleepSession, at: Long): SleepSession = when {
        at >= s.endMs || at < s.endMs - MAX_AUTHORED_NIGHT_MS || at == s.startMs -> s
        at < s.startMs -> SleepSession(at, s.endMs, listOf(SleepRun(at, s.startMs, UNSTAGED)) + s.runs)
        else -> SleepSession(
            at, s.endMs,
            s.runs.filter { it.endMs > at }.map { if (it.startMs < at) it.copy(startMs = at) else it },
        )
    }

    private fun withEnd(s: SleepSession, at: Long): SleepSession = when {
        at <= s.startMs || at > s.startMs + MAX_AUTHORED_NIGHT_MS || at == s.endMs -> s
        at > s.endMs -> SleepSession(s.startMs, at, s.runs + SleepRun(s.endMs, at, UNSTAGED))
        else -> SleepSession(
            s.startMs, at,
            s.runs.filter { it.startMs < at }.map { if (it.endMs > at) it.copy(endMs = at) else it },
        )
    }

    private const val MAX_AUTHORED_NIGHT_MS = 18 * 3_600_000L
}
