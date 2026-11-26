package com.sysadmindoc.ruledeck.widget

import com.opentasker.widget.TaskRunActivity
import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Which tasks may be started through [TaskRunActivity] — the screen a shortcut or widget tap lands on
 * (白い熊, 2026-11-24).
 *
 * That screen is exported on purpose: a launcher such as 白い熊 雷起動盤 fires a shortcut's intent
 * itself. It used to run whatever task was named, for any app that asked — harmless only as long as
 * no task was dangerous to start. Who is asking cannot be checked: Android names the caller of an
 * activity reliably only from 14/15 on, and the Master's EMUI does not. So the permission sits on
 * the TASK instead:
 *
 * - A task is allowed once 白い熊 made a shortcut for it in the app, switched "Shortcuts may start
 *   it" on in the task's card, or answered the one-time question that an unallowed request shows.
 * - A tap on one of the app's OWN widgets never asks: the widget's PendingIntent carries [seal], a
 *   secret of this install that no other app can read out of a PendingIntent or guess.
 *
 * Tasks are named, not id'd (the workspace's reference-by-name rule), so a re-import keeps the
 * permission. Device-local: the file is not exported, so no import or restore can allow a task.
 */
object ShortcutGate {
    private const val PREFS_FILE = "jiyusagyoban_shortcut_gate"
    private const val KEY_ALLOWED = "allowed_tasks"
    private const val KEY_SEAL = "seal"

    /** On an intent built by this app for one of its own widgets. */
    const val EXTRA_SEAL = "com.opentasker.widget.SEAL"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun allowed(context: Context, taskName: String): Boolean =
        taskName in prefs(context).getStringSet(KEY_ALLOWED, emptySet()).orEmpty()

    fun setAllowed(context: Context, taskName: String, value: Boolean) {
        if (taskName.isEmpty()) return
        val p = prefs(context)
        val now = p.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()
        p.edit().putStringSet(KEY_ALLOWED, if (value) now + taskName else now - taskName).commit()
    }

    private fun seal(context: Context): String =
        prefs(context).getString(KEY_SEAL, null)?.takeIf { it.isNotEmpty() } ?: run {
            val made = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs(context).edit().putString(KEY_SEAL, made).commit()
            made
        }

    /** Marks [intent] as built by this app for its own widget. Only ever put into a PendingIntent. */
    fun seal(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_SEAL, seal(context))

    fun isSealed(context: Context, intent: Intent): Boolean {
        val given = intent.getStringExtra(EXTRA_SEAL) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), seal(context).toByteArray())
    }
}
