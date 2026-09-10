package com.opentasker.core.huawei

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A flag that is set while the band link is open, so an ending that never happened leaves a trace.
 *
 * ## The question this exists to answer
 *
 * The band serves one host and goes on holding the slot after that host has gone: on 2026-09-10 it
 * refused every connection twice, hours after a good sync, and only restarting the BAND cleared it
 * (see the `huawei-band-stale-session` note). What leaves it in that state was the open question.
 *
 * [HuaweiSessionGuard] closes the transport on every path the process survives — it was written for
 * an earlier bug of exactly that shape. So the remaining suspect is the process NOT surviving: an
 * app update force-stops it, and a build was installed between the good sync and the failure both
 * times. That is a hypothesis, and until now there was no way to test it: `logcat` had rolled by the
 * time anyone looked, and the run log is in a table no export carries and no cable can read.
 *
 * ## Why a sticky flag rather than a log
 *
 * A log records what happened while something was there to write it. This records the ABSENCE of an
 * ending: set when the link opens, cleared when it closes, so a flag still standing at the next
 * start means the close never ran — which is exactly what a killed process looks like, and what
 * nothing else can distinguish from a clean session followed by a band that dropped the slot on its
 * own. It survives process death by construction, which is the whole point.
 *
 * ## Written with commit(), not apply()
 *
 * `apply()` writes to memory now and to disk when it gets round to it. A marker whose entire job is
 * to survive being killed cannot be written that way — the kill is the thing that would lose it.
 * The clear is committed too: a lost clear would report an unclean session that was actually fine,
 * and a diagnostic that cries wolf is worse than none. One small write per session, not per frame.
 */
object HuaweiSessionMarker {
    private const val PREFS = "huawei_session"
    private const val KEY_SINCE = "open_since"

    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** The link is open. Any earlier mark still standing is returned — see [takeUnclean]. */
    fun open(context: Context) {
        prefs(context).edit().putLong(KEY_SINCE, System.currentTimeMillis()).commit()
    }

    /** The link is closed, cleanly. */
    fun close(context: Context) {
        prefs(context).edit().remove(KEY_SINCE).commit()
    }

    /**
     * If a previous session never closed, say when it started — and clear it, so it is reported once.
     *
     * Called BEFORE opening a new link, which is the only moment the two can be told apart: after
     * [open] the mark belongs to the session now running.
     */
    fun takeUnclean(context: Context): String? {
        val since = prefs(context).getLong(KEY_SINCE, 0L)
        if (since <= 0L) return null
        prefs(context).edit().remove(KEY_SINCE).commit()
        return STAMP.format(Date(since))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
