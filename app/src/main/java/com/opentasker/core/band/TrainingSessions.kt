package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit

/**
 * Sessions 白い熊 marked by hand — the only honest way this band can see strength work.
 *
 * ## Why marking rather than detecting
 *
 * 白い熊 lifted on 2026-08-09 between 16:10 and 16:30, and that twenty minutes is the entire
 * calibration set. What the band recorded:
 *
 * | | |
 * |---|---|
 * | spot readings (10-min cadence) | **82, 93, 90 bpm** — the 71st, 95th and 91st percentile of 白い熊's ordinary waking readings |
 * | periodic readings in the same window | **62 and 70 bpm** — at or below resting, completely blind to it |
 * | steps | 17–36/min — low but NOT zero, so "no steps" is the wrong test |
 * | skin temperature | 36.0 °C before and after — no movement at all |
 *
 * So the whole signal is two or three spot readings around the 90th percentile. A threshold high
 * enough to exclude an ordinary day (p95 ≈ 93 bpm) catches **one** of those three; a threshold loose
 * enough to catch the session finds 76 "sessions" in ten days, one of them three hours long. That is
 * not a tuning problem — three samples is not enough information to separate lifting from sitting up.
 *
 * The over-crediting that killed the heart-rate load channel was entirely an artefact of integrating
 * it across the whole day. **Inside a window 白い熊 has marked, it is exactly the right instrument**,
 * which is why this file exists: bound the window by hand, and the arithmetic becomes legitimate.
 *
 * This is an automation app, so marking costs a tap on a shortcut or widget wired to `band.session`.
 *
 * ## Storage
 *
 * SharedPreferences, one line per session — same reasoning as [RecoveryLog]. These are authored, not
 * synced, so they do not belong in the table the band writes to, and they need no migration.
 */
object TrainingSessions {

    private const val PREFS = "training_sessions"
    private const val KEY_OPEN = "open_start"
    private const val KEY_OPEN_LABEL = "open_label"
    private const val KEY_LIST = "sessions"

    /** A marked window. [endMs] is exclusive. */
    data class Session(val startMs: Long, val endMs: Long, val label: String) {
        val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
    }

    /** Longer than this and an unclosed session is a forgotten tap, not a workout. */
    const val MAX_OPEN_MINUTES = 240

    /**
     * Shorter than this and it was a double-tap, not a session.
     *
     * Found the honest way: toggling the task twice in a row while testing recorded a one-second
     * "workout", which would then have sat in the week's count contributing nothing. A workout has a
     * floor and five minutes is comfortably under any real one.
     */
    const val MIN_SESSION_MINUTES = 5

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Start a session now. A second start replaces the first — the earlier tap was a mistake. */
    fun start(context: Context, atMs: Long, label: String) {
        prefs(context).edit {
            putLong(KEY_OPEN, atMs)
            putString(KEY_OPEN_LABEL, label)
        }
    }

    fun openStart(context: Context): Long? = prefs(context).getLong(KEY_OPEN, 0L).takeIf { it > 0L }

    /**
     * Close the open session, returning it, or null when nothing was open.
     *
     * An open session older than [MAX_OPEN_MINUTES] is discarded rather than closed: 白い熊 tapped
     * start and forgot, and recording a six-hour "session" would corrupt the week's load far worse
     * than losing one workout.
     */
    fun end(context: Context, atMs: Long): Session? {
        val start = openStart(context) ?: return null
        val label = prefs(context).getString(KEY_OPEN_LABEL, "").orEmpty()
        prefs(context).edit { remove(KEY_OPEN); remove(KEY_OPEN_LABEL) }
        val minutes = (atMs - start) / 60_000L
        if (atMs <= start) return null
        if (minutes > MAX_OPEN_MINUTES) return null
        if (minutes < MIN_SESSION_MINUTES) return null
        return log(context, Session(start, atMs, label))
    }

    /** Record a complete session directly — for a task that knows the duration up front. */
    fun log(context: Context, session: Session): Session {
        val existing = all(context).filterNot { it.startMs == session.startMs }
        val merged = (existing + session).sortedBy { it.startMs }.takeLast(MAX_STORED)
        prefs(context).edit { putString(KEY_LIST, merged.joinToString("\n") { encode(it) }) }
        return session
    }

    fun all(context: Context): List<Session> =
        prefs(context).getString(KEY_LIST, "").orEmpty()
            .lineSequence()
            .mapNotNull(::decode)
            .sortedBy { it.startMs }
            .toList()

    fun clear(context: Context) = prefs(context).edit { remove(KEY_LIST); remove(KEY_OPEN) }

    /** `start|end|label`, tab-free so a label containing a bar is the only thing that can confuse it. */
    private fun encode(s: Session) = "${s.startMs}|${s.endMs}|${s.label.replace('|', '/')}"

    private fun decode(line: String): Session? {
        val parts = line.trim().split('|', limit = 3)
        if (parts.size < 2) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].toLongOrNull() ?: return null
        if (end <= start) return null
        return Session(start, end, parts.getOrNull(2).orEmpty())
    }

    /** Roughly a year of daily training. Old sessions fall off the end rather than growing forever. */
    private const val MAX_STORED = 400
}
