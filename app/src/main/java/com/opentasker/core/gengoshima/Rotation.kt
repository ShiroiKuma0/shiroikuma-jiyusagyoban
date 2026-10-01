package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Spaced island rotation — which islands are due today, and when each comes round again.
 *
 * Mikel's routine keeps old islands alive by coming back to them, further apart each time they hold.
 * This is SM-2 applied to a whole island: a session that plays EVERY sentence of an island counts as
 * one review of it, and how well it went is read off the mode rather than asked — recalling it from
 * the English is the strongest evidence, shadowing it the next, merely listening the weakest. A
 * partial session reviews nothing: an island is one monologue and is held or not held as one.
 *
 * Intervals run 1 day, 3 days, then × ease (starting at 2.5, nudged by each review). Every island
 * that has never been reviewed is due, so a new island is always in 「今日の島」.
 */
object Rotation {

    /** How much a session in this mode says about knowing the island (SM-2 quality, 0–5). */
    fun quality(mode: String): Int = when (mode) {
        "recall" -> 5
        "shadow" -> 4
        else -> 3
    }

    /** The island after one complete review on [today]. */
    fun review(island: GengoshimaIslandEntity, mode: String, today: LocalDate, zone: ZoneId): GengoshimaIslandEntity {
        val q = quality(mode)
        val ease = max(1.3, island.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        val interval = when {
            island.intervalDays < 1.0 -> 1.0
            island.intervalDays < 3.0 -> 3.0
            else -> (island.intervalDays * ease).roundToInt().toDouble()
        }
        val status = when {
            interval >= 7 -> "rotating"
            mode == "recall" -> "recalled"
            mode == "shadow" -> "shadowed"
            else -> island.status
        }
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return island.copy(
            ease = ease,
            intervalDays = interval,
            lastReview = System.currentTimeMillis(),
            nextReview = today.plusDays(interval.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
            status = status,
        ).also { check(it.nextReview!! > start) }
    }

    /** Due on [today]: never reviewed, or its next review falls on or before today. */
    fun isDue(island: GengoshimaIslandEntity, today: LocalDate, zone: ZoneId): Boolean {
        val next = island.nextReview ?: return true
        return next < today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
