package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * 白い熊's own daily answer to "how do you feel?", one integer per calendar day.
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
 * SharedPreferences, one `yyyyMMdd=n` pair per day, rather than a Room table. It is a few hundred
 * bytes a year and needs no migration, no DAO and no schema bump — and unlike the band's samples it
 * is authored rather than synced, so it does not belong in the same store as measurements.
 *
 * The scale is 1–5 with 3 as "normal", deliberately short: a 1–10 scale invites false precision from
 * an instrument whose whole value is that it takes one tap.
 */
object RecoveryLog {

    private const val PREFS = "recovery_log"
    private const val KEY_ENABLED = "enabled"

    const val MIN_RATING = 1
    const val MAX_RATING = 5
    const val NEUTRAL = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
