package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * 白い熊's own daily answer to "how do you feel?", one integer per night.
 *
 * Keyed by the MORNING 白い熊 woke on and answered, which is the date the rated night ENDED. A night
 * begun at 23:09 and one begun at 00:21 both end on the morning they are rated on, where their start
 * dates differ by a day — see [migrateToMorningKeys], and [migrateToNightKeys] for the start-keying
 * this replaced.
 *
 * ## Why this modest thing is here at all
 *
 * Because it is, on the evidence, the single most valuable input in the whole 回復 feature — more
 * sensitive than everything the band measures put together. Four independent literature reviews
 * arrived at that from different directions:
 *
 * - Saw, Main & Gastin (2016, *BJSM*), **56 studies**: subjective and objective measures generally
 *   did not correlate, and the subjective ones reflected acute and chronic training load *"with
 *   superior sensitivity and consistency"*.
 * - Figueiredo et al. (2022, *J Sports Sci*), a 3-arm RCT in 36 runners: a daily questionnaire beat
 *   HRV-guided training on BOTH outcomes — 5 km time trial −12.8 % against −8.3 %, and −6.0 % for a
 *   fixed plan.
 * - Rabbani et al. (2018): through a congested fixture period the Hooper index moved +49.8 % while
 *   HRV moved −2.1 % to +8.2 %, i.e. nothing.
 * - Nuuttila et al. (2025, *Sensors*): through a two-week overload block perceived strain and muscle
 *   soreness rose significantly (p < 0.001) while the nightly sensor metrics *"remained unchanged"*.
 *
 * It is also the third leg of the only validated composite shape — Nuuttila's ≥2-of-3 counting rule
 * pairs nocturnal HR and an exercise index with **subjective readiness**, and reaches PPV 92 % /
 * NPV 100 % that way.
 *
 * ## Storage
 *
 * SharedPreferences, one `yyyyMMdd=n` pair per night, rather than a Room table. It is a few hundred
 * bytes a year and needs no migration, no DAO and no schema bump — and unlike the band's samples it
 * is authored rather than synced, so it does not belong in the same store as measurements.
 *
 * The scale is 1–5 with 3 as "normal", deliberately short: a 1–10 scale invites false precision from
 * an instrument whose whole value is that it takes one tap. Since 2026-08-12 **1 is the best night
 * and 5 the worst** — see [migrateToBestFirst] for what that cost the ratings already on file.
 */
object RecoveryLog {

    private const val PREFS = "recovery_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NIGHT_KEYED = "night_keyed"
    private const val KEY_BEST_FIRST = "best_first"
    private const val KEY_MORNING_KEYED = "morning_keyed"

    /**
     * The one rating 白い熊 retired by hand, dropped as part of [migrateToMorningKeys].
     *
     * It was filed against 2026-08-14, a date the band recorded no night starting on, and it described
     * the same night as the rating on 2026-08-13 — which under morning keys moves ONTO 2026-08-14.
     * Two answers, one morning, and only 白い熊 could say which was meant: "Delete the current 14
     * score." (2026-08-16.)
     */
    internal const val RETIRED_KEY = 20260814L

    const val MIN_RATING = 1
    const val MAX_RATING = 5
    const val NEUTRAL = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also(::migrateToNightKeys)
            .also(::migrateToBestFirst)

    /**
     * Re-key ratings written before the night-keying fix, once.
     *
     * The fix of 2026-08-10 changed what a key MEANS — from the calendar day the answer was typed on
     * to the start date of the night it describes, which is the day before — without moving the
     * entries already on file. Every one of them therefore read a night late: the answer typed on the
     * morning of the 10th sat under `20260810`, which now names the night that started that evening,
     * so on the 11th the card offered yesterday's answer as last night's rating. Reported 2026-08-11.
     *
     * Shifting every existing key back one day is exactly right because no rating was entered between
     * the fix shipping and this migration (白い熊, 2026-08-11) — so nothing on file is already
     * night-keyed and there is nothing here that could be shifted twice. The flag makes sure of the
     * rest: the store cannot tell the two schemes apart by inspection, so running this a second time
     * would silently walk the whole history backwards.
     */
    private fun migrateToNightKeys(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_NIGHT_KEYED, false)) return
        val before = ratingsIn(prefs.all)
        val after = shiftedToNightKeys(before)
        prefs.edit {
            // Remove every old key BEFORE writing the new ones: the two sets overlap on a run of
            // consecutive days, so interleaving them would delete a value just written.
            before.keys.forEach { remove(keyOf(it)) }
            after.forEach { (date, rating) -> putInt(keyOf(date), rating) }
            putBoolean(KEY_NIGHT_KEYED, true)
        }
    }

    /**
     * Re-number every stored rating for the 2026-08-12 flip, once.
     *
     * The scale ran 1 = Wrecked … 5 = Great until that day and now runs the other way. **The store
     * holds bare integers with no scale marker in them**, so a rating written under the old scheme
     * does not merely display differently afterwards — it means the opposite. 白い熊's four ratings
     * would have read as their own mirror image, and worse, would have gone on feeding the baseline
     * and the adverse count in the wrong direction, silently, for as long as they stayed on file.
     *
     * `6 − n` is the whole conversion: an involution on 1..5, so 2 ↔ 4, 1 ↔ 5, and 3 is its own
     * opposite. The flag is what stops it running twice — the store cannot tell the two schemes apart
     * by inspection, and applying an involution a second time would put everything back.
     */
    private fun migrateToBestFirst(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_BEST_FIRST, false)) return
        val before = ratingsIn(prefs.all)
        prefs.edit {
            flippedToBestFirst(before).forEach { (date, rating) -> putInt(keyOf(date), rating) }
            putBoolean(KEY_BEST_FIRST, true)
        }
    }

    /**
     * Re-key every rating from the night's start date to the MORNING it ended, once.
     *
     * Until 2026-08-16 a rating was filed under the date the night STARTED. That is one day before the
     * morning it was given on whenever the bedtime fell before midnight, and the same day when it fell
     * after — so 白い熊's grid held thirteen scores a tile to the left of the morning they belonged to
     * and one on it, with nothing to show which was which. It also made the morning after a night the
     * band missed unfileable: its name was already taken by the night that began that same evening.
     *
     * The move is driven by the band's own sessions rather than by arithmetic on the key. `+1 day`
     * would be right for thirteen of the fourteen nights on file and wrong for the fourteenth, and a
     * rating moved onto the wrong morning is worse than one left alone: it looks authored, it feeds
     * the baseline and the ≥2-of-3 count, and nothing about it ever looks wrong again. So
     * [morningOfNightStarting] is built from the recorded nights — start date → the date it ended —
     * and a rating moves only where a real night says where it ends.
     *
     * Refuses rather than guesses, and does NOT set its flag when it refuses, so the next load tries
     * again with whatever has since synced:
     *
     * - no recorded nights at all — the sessions have not loaded yet, and migrating against an empty
     *   map would strand every rating and then mark the job done;
     * - two ratings landing on one morning, which would silently destroy one of them.
     *
     * A rating whose night is no longer among the recorded sessions cannot be placed; it keeps its key
     * and is counted in the return value rather than moved on a guess.
     */
    fun migrateToMorningKeys(context: Context, morningOfNightStarting: Map<Long, Long>): Int {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MORNING_KEYED, false)) return 0
        if (morningOfNightStarting.isEmpty()) return -1
        val before = ratingsIn(prefs.all)
        val move = movedToMorningKeys(before, morningOfNightStarting) ?: return -1
        prefs.edit {
            // Every old key goes before any new one is written: the two sets overlap wherever a night
            // began after midnight, so interleaving would delete a value just written.
            before.keys.forEach { remove(keyOf(it)) }
            move.moved.forEach { (date, rating) -> putInt(keyOf(date), rating) }
            putBoolean(KEY_MORNING_KEYED, true)
        }
        return move.unresolved.size
    }

    /** What [movedToMorningKeys] worked out: where everything lands, and what it could not place. */
    internal data class MorningMove(val moved: Map<Long, Int>, val unresolved: Set<Long>)

    /**
     * The pure part of [migrateToMorningKeys]. Null means refuse — two ratings want one morning.
     *
     * [RETIRED_KEY] is dropped before anything else, which is also what keeps 白い熊's own store free
     * of exactly that collision.
     */
    internal fun movedToMorningKeys(
        ratings: Map<Long, Int>,
        morningOfNightStarting: Map<Long, Long>,
    ): MorningMove? {
        val kept = ratings - RETIRED_KEY
        val moved = HashMap<Long, Int>(kept.size)
        val unresolved = HashSet<Long>()
        for ((date, rating) in kept) {
            val morning = morningOfNightStarting[date]
            if (morning == null) {
                unresolved += date
                if (moved.put(date, rating) != null) return null
            } else if (moved.put(morning, rating) != null) {
                return null
            }
        }
        return MorningMove(moved, unresolved)
    }

    /**
     * The pure part of [migrateToBestFirst]. Keys are untouched, so nothing can collide or be lost.
     */
    internal fun flippedToBestFirst(ratings: Map<Long, Int>): Map<Long, Int> =
        ratings.mapValues { (_, rating) -> MIN_RATING + MAX_RATING - rating }

    /** The `yyyyMMdd`→rating pairs in a raw preferences map, ignoring [KEY_ENABLED] and friends. */
    private fun ratingsIn(all: Map<String, Any?>): Map<Long, Int> =
        all.entries.mapNotNull { (key, value) ->
            val date = key.toLongOrNull() ?: return@mapNotNull null
            val rating = (value as? Int)?.takeIf { it in MIN_RATING..MAX_RATING } ?: return@mapNotNull null
            date to rating
        }.toMap()

    /**
     * The pure part of [migrateToNightKeys]: every rating moved to the day before.
     *
     * Injective, so no two ratings can land on the same night and none is lost. A key that is not a
     * real date is dropped rather than guessed at — there is nothing to recover from `20260231`.
     */
    internal fun shiftedToNightKeys(ratings: Map<Long, Int>): Map<Long, Int> =
        ratings.mapNotNull { (date, rating) -> previousDay(date)?.let { it to rating } }.toMap()

    /** `yyyyMMdd` one calendar day earlier, or null if the key is not a real date. */
    private fun previousDay(date: Long): Long? = runCatching {
        java.time.LocalDate.of(
            (date / 10_000L).toInt(),
            ((date / 100L) % 100L).toInt(),
            (date % 100L).toInt(),
        ).minusDays(1)
    }.getOrNull()?.let { it.year * 10_000L + it.monthValue * 100L + it.dayOfMonth }

    /** `yyyyMMdd` — the same local-date key shape the band's own records use. */
    fun keyOf(localDate: Long): String = localDate.toString()

    fun rating(context: Context, localDate: Long): Int? =
        prefs(context).getInt(keyOf(localDate), -1).takeIf { it in MIN_RATING..MAX_RATING }

    fun setRating(context: Context, localDate: Long, rating: Int) =
        prefs(context).edit { putInt(keyOf(localDate), rating.coerceIn(MIN_RATING, MAX_RATING)) }

    fun clear(context: Context, localDate: Long) = prefs(context).edit { remove(keyOf(localDate)) }

    /**
     * Every rating on record, as `yyyyMMdd` → 1–5.
     *
     * Returned wholesale because the baseline needs the distribution, not a window: with one value a
     * day even a year is a few hundred entries.
     */
    fun all(context: Context): Map<Long, Int> =
        prefs(context).all.entries.mapNotNull { (k, v) ->
            val date = k.toLongOrNull() ?: return@mapNotNull null
            val rating = (v as? Int)?.takeIf { it in MIN_RATING..MAX_RATING } ?: return@mapNotNull null
            date to rating
        }.toMap()

    /**
     * Whether to ask at all.
     *
     * On by default, because a passive-only 回復 section leaves the most sensitive signal on the
     * table — but it is 白い熊's to switch off from 健康の設定 without losing the rest of the card.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, value) }
}
