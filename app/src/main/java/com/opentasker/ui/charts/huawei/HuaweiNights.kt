package com.opentasker.ui.charts.huawei

import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.HuaweiSleepEntity
import com.opentasker.ui.charts.SleepRun
import com.opentasker.ui.charts.SleepSession

/**
 * Nights, from whichever band was on the wrist that night.
 *
 * The same rule the charts follow: the Huawei band owns every night from its first recorded one
 * onward, and the Hume band owns the era before that. **No night is ever assembled from both.** A
 * recovery baseline built by mixing two devices' staging would be a number about neither of them —
 * consumer sleep staging disagrees with itself across devices far more than it disagrees with a
 * clock, so blending is worse here than almost anywhere else on this screen.
 *
 * ## Why the stage codes have to be translated rather than reused
 *
 * The two bands number their stages differently, and the numbers overlap — so an untranslated code
 * does not fail, it silently means the wrong stage. The Huawei band uses 1 light, 2 REM, 3 deep,
 * 4 awake; the shared [SleepSession] uses the Hume band's '1' deep, '2' light, '3' REM, '5' awake.
 * Reading a Huawei '3' as deep when it means REM would move an hour a night into the wrong column
 * and every derived figure with it.
 */
object HuaweiNights {

    /** Huawei stage → the shared code. See the class note: these numbers do NOT correspond. */
    private fun codeOf(stage: Int): Char = when (stage) {
        1 -> '2'   // light
        2 -> '3'   // REM
        3 -> '1'   // deep
        4 -> '5'   // awake
        // The watch's unstaged sleep. Asleep, so it counts — a morning return to bed is filed this
        // way, and as '?' it was neither sleep nor awake.
        5 -> com.opentasker.ui.charts.SleepShape.UNSTAGED
        else -> '?'
    }

    /**
     * Every night on record, oldest first, each attributed to exactly one band.
     *
     * [cutoverMs] is where the Huawei band's own record begins. Nights ending before it come from
     * the Hume store; nights from it onward come from the Huawei store. A night that straddles the
     * cutover — the first night the new band was worn — is taken from the Huawei side, because that
     * is the band that recorded it through to morning.
     */
    suspend fun all(
        db: AppDatabase,
        cutoverMs: Long?,
        humeSessions: List<SleepSession>,
    ): List<SleepSession> {
        val huawei = huaweiSessions(db)
        val boundary = cutoverMs ?: Long.MAX_VALUE
        val older = humeSessions.filter { it.endMs < boundary }
        return (older + huawei).sortedBy { it.startMs }
    }

    /** The Huawei band's own nights, rebuilt from stored segments. */
    private suspend fun huaweiSessions(db: AppDatabase): List<SleepSession> =
        nights(storedParts(db)).map { night ->
            SleepSession(
                startMs = night.first().startSeconds * 1000L,
                endMs = night.last().endSeconds * 1000L,
                runs = segmentsOf(night).map { (start, duration, stage) ->
                    SleepRun(startMs = start * 1000L, endMs = (start + duration) * 1000L, code = codeOf(stage))
                },
            )
        }

    /**
     * The newest night as the watch's own type, for the sleep card and its hypnogram — assembled by
     * the same [nights] as every other figure, so the card and the statistics never disagree.
     */
    suspend fun latestNight(db: AppDatabase): com.opentasker.core.huawei.HuaweiSleep.Session? {
        val night = nights(storedParts(db)).lastOrNull() ?: return null
        return com.opentasker.core.huawei.HuaweiSleep.Session(
            startSeconds = night.first().startSeconds,
            endSeconds = night.last().endSeconds,
            segments = segmentsOf(night).map { (start, duration, stage) ->
                com.opentasker.core.huawei.HuaweiSleep.Segment(
                    start, duration, com.opentasker.core.huawei.HuaweiSleep.Stage.of(stage),
                )
            },
        )
    }

    /** One session as the watch stored it: its declared span and its segments. */
    data class Part(val startSeconds: Long, val endSeconds: Long, val rows: List<HuaweiSleepEntity>) {
        /** Real night sleep, staged light / REM / deep — not the watch's own stage-5 nap fragments. */
        val staged: Boolean get() = rows.any { it.stage in 1..3 }
    }

    /**
     * How far apart two sleeps may be and still be one night: two hours.
     *
     * 白い熊, 2026-11-08: “I slept 6+ hours, then got up for an hour, and then slept another 3 hours
     * — these are the night's sleep.” The watch files them as two sessions. On record: 2026-10-09/10,
     * 7.5 h, up 68 minutes, then 3.3 h — counted until now as a night and a separate sleep. The
     * smallest gap that genuinely separates two sleeps is 177–197 minutes (an evening nap before a
     * night), so two hours joins the first kind and never the second.
     */
    const val JOIN_GAP_SECONDS = 2 * 3600L

    /**
     * Back to bed in the morning: an unstaged sleep the watch files as a nap (stage 5), starting
     * before [MORNING_UNTIL_MINUTE] and within [MORNING_GAP_SECONDS] of the night's end, is the rest
     * of that night.
     *
     * 白い熊, 2026-11-08: up from 04:01 to 06:27, then asleep to 08:40 — one night. The watch made the
     * second sleep a single stage-5 block, and the gap was 2 h 26 m. The same shape follows several
     * nights on record (07:22 after a 05:40 wake, 08:53 after 06:42, 09:36 after 07:39). Before
     * 10:00, 白い熊's line (2026-11-08) — a sleep starting later is a nap and stays its own; the long gaps that separate a nap from a
     * night (177–197 min) all sit BEFORE a night, never after one, so this cannot catch them.
     */
    const val MORNING_GAP_SECONDS = 3 * 3600L
    const val MORNING_UNTIL_MINUTE = 10 * 60

    private suspend fun storedParts(db: AppDatabase): List<Part> {
        val rows = runCatching { db.huaweiSleepDao().window(Long.MIN_VALUE, Long.MAX_VALUE) }.getOrDefault(emptyList())
        return rows.groupBy { it.sessionStart to it.sessionEnd }
            .map { (span, r) -> Part(span.first, span.second, r.sortedBy { it.startSeconds }) }
    }

    /**
     * Sessions → nights. Two steps, both pure:
     *
     * 1. **A superseded copy is dropped.** A sync in the middle of a night stores what the watch had
     *    so far; the morning's sync stores the finished night under the same bed time — re-cut, so
     *    its segments do not overwrite the earlier ones. Both were read back as one night: on
     *    2026-11-06/07 a 2.8 h snapshot from 23:53 on top of the full 10.3 h, and 健康 reported 13 h
     *    11 m asleep. Of overlapping sessions only the one spanning longest is kept.
     * 2. **Sleeps less than [JOIN_GAP_SECONDS] apart are one night**, when both are staged sleep; and
     *    a morning return to bed joins the night before it (see [MORNING_GAP_SECONDS]). The stage-5
     *    fragments the watch scatters through the afternoon are never joined to anything.
     */
    fun nights(parts: List<Part>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<List<Part>> {
        val kept = ArrayList<Part>()
        for (p in parts.sortedBy { it.startSeconds }) {
            val last = kept.lastOrNull()
            if (last != null && p.startSeconds < last.endSeconds) {
                if (p.endSeconds - p.startSeconds > last.endSeconds - last.startSeconds) kept[kept.lastIndex] = p
                continue
            }
            kept += p
        }
        val out = ArrayList<MutableList<Part>>()
        for (p in kept) {
            val night = out.lastOrNull()
            val prev = night?.last()
            val gap = if (prev == null) Long.MAX_VALUE else p.startSeconds - prev.endSeconds
            val sameNight = night != null && night.first().staged && (
                (p.staged && prev!!.staged && gap <= JOIN_GAP_SECONDS) ||
                    (gap <= MORNING_GAP_SECONDS && minuteOfDay(p.startSeconds, zone) < MORNING_UNTIL_MINUTE)
                )
            if (sameNight) {
                night!!.add(p)
            } else {
                out += mutableListOf(p)
            }
        }
        return out
    }

    /**
     * A night's segments as (start, duration, Huawei stage), the time between its sleeps as awake —
     * that hour was spent up, and the hypnogram should show it, not a hole.
     */
    fun segmentsOf(night: List<Part>): List<Triple<Long, Int, Int>> {
        val out = ArrayList<Triple<Long, Int, Int>>()
        night.forEachIndexed { i, part ->
            if (i > 0) {
                val gapFrom = maxOf(night[i - 1].endSeconds, night[i - 1].rows.maxOf { it.startSeconds + it.durationSeconds })
                val gapTo = part.rows.minOf { it.startSeconds }
                if (gapTo > gapFrom) out += Triple(gapFrom, (gapTo - gapFrom).toInt(), AWAKE)
            }
            part.rows.forEach { out += Triple(it.startSeconds, it.durationSeconds, it.stage) }
        }
        return out
    }

    private fun minuteOfDay(seconds: Long, zone: java.time.ZoneId): Int =
        java.time.Instant.ofEpochSecond(seconds).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    private const val AWAKE = 4
}
