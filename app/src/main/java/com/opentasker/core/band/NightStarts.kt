package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * When 白い熊 actually fell asleep, where the watch says otherwise — one instant per morning.
 *
 * The watch dates a night from the first stage it scored, and it can only score a night it was
 * worn for. On 2026-11-03 白い熊 fell asleep at 01:30 without it, put it on at about 03:00, and the
 * watch began the night at 04:04 — a three-hour night that was really five and a half. Nothing
 * measured can fix that, so it is authored, like the rating and the note beside it.
 *
 * Keyed like [RecoveryLog]: `yyyyMMdd` of the MORNING the night ended, so one correction belongs to
 * exactly one row of the night table whatever side of midnight the bedtime fell. The value is the
 * epoch-millisecond instant, not a clock time, because a bedtime of 23:30 and one of 01:30 belong
 * to different calendar days and a bare "HH:mm" could not say which.
 *
 * What it does to the night is `SleepShape.withNightStarts`: an earlier start adds the gap as
 * unstaged sleep — asleep, but no stage the watch never measured; a later one trims the front off.
 * SharedPreferences for the same reason as the rating: a few bytes, authored, no schema.
 */
object NightStarts {

    private const val PREFS = "night_starts"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every correction on file, morning key → the instant 白い熊 fell asleep. */
    fun all(context: Context): Map<Long, Long> =
        prefs(context).all.mapNotNull { (k, v) ->
            val key = k.toLongOrNull() ?: return@mapNotNull null
            (v as? Long)?.let { key to it }
        }.toMap()

    /** Set the night ending on [morningKey] to start at [atMs]; null withdraws the correction. */
    fun set(context: Context, morningKey: Long, atMs: Long?) {
        prefs(context).edit {
            if (atMs == null) remove(morningKey.toString()) else putLong(morningKey.toString(), atMs)
        }
    }
}
