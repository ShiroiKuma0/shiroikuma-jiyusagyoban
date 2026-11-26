package com.sysadmindoc.ruledeck.core.transfer

import android.content.Context
import android.content.Intent
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A mark on an intent this app sends TO ITSELF, so that one of its own doors can tell "a task of
 * mine asked" from "some app asked" (白い熊, 2026-11-24, audit V-05).
 *
 * A broadcast carries no sender. The state-export door takes a `path` — any directory the app can
 * write — and that was open to every app on the phone; the one legitimate user of `path` is this
 * app's own 保存復元 project, whose tasks send the request with `intent.send`. `intent.send` marks an
 * intent that is addressed to this package alone; the door honours `path` only with the mark.
 *
 * The mark is a secret of this install. It is only ever put on an intent restricted to this package,
 * so no other app receives it. Device-local, never exported.
 */
object SelfSeal {
    private const val PREFS_FILE = "jiyusagyoban_self_seal"
    private const val KEY_SEAL = "seal"
    const val EXTRA_SEAL = "shiroikuma.jiyusagyoban.extra.SELF_SEAL"

    private fun seal(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SEAL, null)?.takeIf { it.isNotEmpty() } ?: run {
            val made = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_SEAL, made).commit()
            made
        }
    }

    /** Marks [intent] when — and only when — it can reach nobody but this app. */
    fun markIfOwn(context: Context, intent: Intent) {
        val own = context.packageName
        val target = intent.component?.packageName ?: intent.`package`
        if (target == own) intent.putExtra(EXTRA_SEAL, seal(context))
    }

    fun isOwn(context: Context, intent: Intent): Boolean {
        val given = intent.getStringExtra(EXTRA_SEAL) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), seal(context).toByteArray())
    }
}
