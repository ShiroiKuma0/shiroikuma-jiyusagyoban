package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * 白い熊's own daily answer to "how do you feel?", one integer per night.
 *
 * Keyed by the date the rated night STARTED, not by the morning the answer was typed on — a night
 * begun at 23:xx on the 9th is never the 10th, and the marker looks a night's rating up by the
 * night's own start date. See [migrateToNightKeys] for what that cost before it was true.
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
 * an instrument whose whole value is that it takes one tap.
 */
object RecoveryLog {

    private const val PREFS = "recovery_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NIGHT_KEYED = "night_keyed"

    const val MIN_RATING = 1
    const val MAX_RATING = 5
    const val NEUTRAL = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also(::migrateToNightKeys)

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
