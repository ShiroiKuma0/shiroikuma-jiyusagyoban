package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * 白い熊's own words about one day — the free-text companion to [RecoveryLog]'s single digit.
 *
 * ## Why the rating needed a sentence beside it
 *
 * A 1–5 says a night was bad. It cannot say *why*, and by the time the number matters — a month
 * later, reading a run of 4s in the grid — the reason is gone. "Woke at 03:00 and could not get back
 * down", "cat", "second night after the flight" is the difference between a series and a record. The
 * rating stays a single tap precisely so it gets answered every morning; this is where everything the
 * tap cannot carry goes, and it is optional by design.
 *
 * ## Keyed exactly like the rating
 *
 * `yyyyMMdd` of the MORNING 白い熊 woke on — the same key [RecoveryLog] files the rating under, so a
 * day's score and a day's note are one row in two stores and can never drift apart. Notes are new as
 * of 2026-09-02 and therefore start life morning-keyed: none of [RecoveryLog]'s three historical
 * re-keyings (night keys, best-first, morning keys) has anything here to move. **If a fourth ever
 * moves the ratings, it has to move these with them** — a note filed against a night it does not
 * describe is worse than no note, for the same reason a rating on the wrong night is.
 *
 * ## Storage: one preferences file per KIND of note
 *
 * Not Room, for [RecoveryLog]'s reason: this is authored rather than synced, a few hundred bytes a
 * year, and it needs no schema. And not [RecoveryLog]'s own file either, though the keys match —
 * a `putString("20260902", …)` there would land on the very key the rating occupies and silently
 * replace an `Int` with a `String`. A dedicated file is also what the sister apps settled on
 * (`AppNotesManager` in 白い熊 応用管理, `KojikiAppNotes` in 白い熊 考直), and for the same practical
 * payoff: a whole-file exporter carries the notes with zero extra code.
 *
 * ## Blank deletes
 *
 * Saving an empty field removes the note rather than storing `""`, so "has a note" is one question
 * with one answer everywhere it is asked. That rule is the sister apps' too, and it is what lets a
 * reader undo a note without a second, differently-shaped control for deleting one.
 */
class DayNotes private constructor(private val prefsName: String) {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** The note filed under [morningKey] (`yyyyMMdd`), or null when there is none. */
    fun note(context: Context, morningKey: Long): String? =
        prefs(context).getString(morningKey.toString(), null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Persist (or, on blank text, delete) the note for [morningKey]. */
    fun setNote(context: Context, morningKey: Long, text: CharSequence?) {
        val trimmed = text?.toString()?.trim().orEmpty()
        val key = morningKey.toString()
        prefs(context).edit { if (trimmed.isEmpty()) remove(key) else putString(key, trimmed) }
    }

    /**
     * Every note on file, `yyyyMMdd` → text.
     *
     * Anything that is not a date key holding non-blank text is skipped rather than guessed at, so a
     * future flag stored alongside these cannot arrive in the register as a day.
     */
    fun all(context: Context): Map<Long, String> =
        prefs(context).all.mapNotNull { (key, value) ->
            val date = key.toLongOrNull() ?: return@mapNotNull null
            val text = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            date to text
        }.toMap()

    companion object {
        /**
         * What 白い熊 wrote about a MORNING, beside its 1–5 rating. Keyed by the morning key.
         *
         * @see RecoveryLog
         */
        val RECOVERY = DayNotes("recovery_notes")

        /**
         * What 白い熊 wrote about a DAY'S 機能訓練, beside the tick. Keyed by the calendar day.
         *
         * **A separate file, not a second use of [RECOVERY].** They would collide — both are keyed
         * `yyyyMMdd` and both describe the same date — and they are not the same note: one says how
         * the night went, the other what the rehab session was. Merging them would silently
         * overwrite whichever was written second.
         */
        val REHAB = DayNotes("rehab_notes")
    }
}
