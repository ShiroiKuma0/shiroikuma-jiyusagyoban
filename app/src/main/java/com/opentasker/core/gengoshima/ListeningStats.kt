package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaSessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the listening history adds up to — per day, per hour of the day, and in a row.
 *
 * Pure, so every number on 「言語島 統計」 is a test rather than a hope. Minutes are the time actually
 * listened (the player's own count), not the time the window was open; a session the app never
 * closed (killed mid-way) still counts what it logged.
 */
object ListeningStats {

    data class Summary(
        /** Epoch day → milliseconds listened that day. */
        val perDay: Map<Long, Long>,
        /** Hour of day (0–23) → milliseconds listened in that hour, over the whole history. */
        val perHour: LongArray,
        /** Days in a row up to today — or up to yesterday, if today has not been listened to yet. */
        val streak: Int,
        val longest: Int,
        val totalMs: Long,
        val sessions: Int,
    )

    /** The minutes a session counts: the player's own tally, or its span if the tally is missing. */
    fun listened(s: GengoshimaSessionEntity): Long =
        if (s.listenedMs > 0) s.listenedMs else ((s.endedAt ?: s.startedAt) - s.startedAt).coerceAtLeast(0)

    fun summarise(sessions: List<GengoshimaSessionEntity>, zone: ZoneId, today: LocalDate): Summary {
        val perDay = HashMap<Long, Long>()
        val perHour = LongArray(24)
        for (s in sessions) {
            val ms = listened(s)
            if (ms <= 0) continue
            // Spread over the clock hours the session actually covered, so a session from 07:50 to
            // 08:20 is half 7 o'clock and half 8 — the histogram is about WHEN, not where it began.
            val span = ((s.endedAt ?: (s.startedAt + ms)) - s.startedAt).coerceAtLeast(ms)
            var t = s.startedAt
            val end = s.startedAt + span
            while (t < end) {
                val at = Instant.ofEpochMilli(t).atZone(zone)
                val nextHour = at.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val chunkEnd = minOf(end, nextHour)
                val share = ms * (chunkEnd - t) / span
                perHour[at.hour] += share
                val day = at.toLocalDate().toEpochDay()
                perDay[day] = (perDay[day] ?: 0L) + share
                t = chunkEnd
            }
        }
        val days = perDay.filterValues { it > 0 }.keys
        var streak = 0
        var d = today.toEpochDay().let { if (it in days) it else it - 1 }
        while (d in days) {
            streak++
            d--
        }
        var longest = 0
        var run = 0
        var prev: Long? = null
        for (day in days.sorted()) {
            run = if (prev != null && day == prev + 1) run + 1 else 1
            longest = maxOf(longest, run)
            prev = day
        }
        return Summary(perDay, perHour, streak, longest, sessions.sumOf { listened(it) }, sessions.size)
    }

    /** `1時間 5分` / `12分` / `40秒`. */
    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m >= 60 -> "${m / 60}時間 ${m % 60}分"
            m > 0 -> "${m}分"
            else -> "${ms / 1000}秒"
        }
    }
}
