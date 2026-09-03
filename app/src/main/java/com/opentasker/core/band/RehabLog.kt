package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * 機能訓練 — was the rehab done, on each day. One tick per calendar day.
 *
 * ## Why a tick and not a score
 *
 * [RecoveryLog] asks how a morning FELT, on five steps, because "how do you feel" has degrees.
 * Whether the exercises were done does not: they were or they were not, and any middle step would
 * invite a judgement ("half of them?") that nobody could read back consistently a month later. The
 * value of this record is a run of days, and a run is only legible when every day answers the same
 * question the same way.
 *
 * ## Keyed by the CALENDAR day, not by a morning
 *
 * Deliberately unlike [RecoveryLog], whose key is the morning a night is filed under. Rehab is done
 * during a day, so it belongs to that day — the one the calendar tile is drawn for. There is no
 * night to attribute it across and no midnight boundary to get wrong.
 *
 * ## Storage, and the one seeded day
 *
 * SharedPreferences, `yyyyMMdd=true`, for [RecoveryLog]'s reasons: authored rather than synced, a
 * few hundred bytes a year, no schema and no migration. Only days that were DONE are stored — a day
 * with no entry is a day it was not done, so the file holds the ticks and nothing else.
 *
 * [seedTodayOnce] writes today's date the first time the store is opened, because 白い熊 asked the
 * history to start with today marked (2026-09-03) and there is no earlier record to import. It runs
 * exactly once, behind its own flag: without that, deleting today's tick would see an empty store on
 * the next launch and put it straight back, which is a record that argues with the person keeping it.
 */
object RehabLog {

    private const val PREFS = "rehab_log"
    private const val KEY_SEEDED = "seeded"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `yyyyMMdd` for a local date. The same shape [RecoveryLog] and `DayNotes` use. */
    fun dateKeyOf(date: java.time.LocalDate): Long =
        date.year * 10_000L + date.monthValue * 100L + date.dayOfMonth

    /** Today, in the device's own zone — the day a tick from the card belongs to. */
    fun today(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        dateKeyOf(java.time.LocalDate.now(zone))

    fun done(context: Context, dateKey: Long): Boolean =
        prefs(context).getBoolean(dateKey.toString(), false)

    /** Tick or un-tick one day. Un-ticking REMOVES the key, so the file holds only the done days. */
    fun setDone(context: Context, dateKey: Long, done: Boolean) {
        val key = dateKey.toString()
        prefs(context).edit { if (done) putBoolean(key, true) else remove(key) }
    }

    /**
     * Every day on record, as `yyyyMMdd`.
     *
     * Anything that is not a date key holding `true` is skipped — the seeded flag lives in this same
     * file and must never arrive in a calendar as a day.
     */
    fun all(context: Context): Set<Long> =
        prefs(context).all.mapNotNullTo(HashSet()) { (key, value) ->
            val date = key.toLongOrNull() ?: return@mapNotNullTo null
            if (value == true) date else null
        }

    /**
     * Mark today as done, once, on the very first open — and never again.
     *
     * The flag is the whole point. "Is the store empty" is not a test for "has this ever run": it is
     * also true the moment 白い熊 un-ticks the only day there is, and a seed that fires then would
     * undo the correction on the next launch.
     */
    fun seedTodayOnce(context: Context, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEDED, false)) return
        p.edit {
            putBoolean(today(zone).toString(), true)
            putBoolean(KEY_SEEDED, true)
        }
    }
}
