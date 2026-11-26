package com.sysadmindoc.ruledeck.core.actions

import android.content.Context
import com.sysadmindoc.ruledeck.core.apps.TargetSignerCheck
import com.sysadmindoc.ruledeck.core.apps.TargetSignerGuard

/**
 * `provider.call` asks an authority and believes the answer — the 携帯 relay believes
 * `shiroikuma.jami.automation` about WHO wrote a message. So the app behind an authority is pinned
 * on first use (白い熊, 2026-11-24, audit V-08): its package name here, its signing certificate in
 * [TargetSignerGuard], as is already done for Termux.
 *
 * Android allows an authority one owner at a time, so this matters on a phone where the real app is
 * gone and another one claims the name — or where it was replaced by a build signed with another key.
 * Either is refused, and the message says where to forget the pin if 白い熊 did it himself.
 *
 * Device-local; not exported.
 */
object ProviderOwnerPins {
    private const val PREFS_FILE = "jiyusagyoban_provider_owners"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Null when [packageName] may answer for [authority]; else why not. */
    fun refuse(context: Context, authority: String, packageName: String): String? {
        val p = prefs(context)
        val pinned = p.getString(authority, null)
        if (pinned == null) {
            p.edit().putString(authority, packageName).commit()
        } else if (pinned != packageName) {
            return "provider '$authority' now belongs to $packageName, not to $pinned as when it was first used — " +
                "not asked. If you changed that app yourself: UI page → Export / Import → “Forget pinned provider apps”."
        }
        val check = runCatching { TargetSignerGuard.forContext(context).check(packageName) }.getOrElse {
            return "could not read the signing certificate of $packageName — provider '$authority' not asked"
        }
        return if (check is TargetSignerCheck.Mismatch) {
            "$packageName is signed with another key than when '$authority' was first used — not asked. " +
                "If you reinstalled it yourself: UI page → Export / Import → “Forget pinned provider apps”."
        } else {
            null
        }
    }

    /** By hand: forget every pinned authority and the signer pins of the apps behind them. */
    fun forgetAll(context: Context): Int {
        val p = prefs(context)
        val packages = p.all.values.filterIsInstance<String>().toSet()
        val guard = TargetSignerGuard.forContext(context)
        packages.forEach { runCatching { guard.restore(it, null) } }
        p.edit().clear().commit()
        return packages.size
    }
}
