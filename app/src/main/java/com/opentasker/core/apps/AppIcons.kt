package com.opentasker.core.apps

import android.content.Context
import android.graphics.drawable.Drawable
import com.opentasker.core.policy.AppFreeze

/**
 * Loading an installed app's icon on a phone where most apps are frozen.
 *
 * ## Why this is not just `getApplicationIcon(pkg)`
 *
 * `PackageManager.getApplicationIcon(String)` re-resolves the package under **plain flags**, and a
 * frozen app is not readable under those: 白い熊's freeze sets `hidden=true` alongside the suspend
 * and disable gates, and a hidden package answers `NameNotFoundException` unless the caller asks for
 * uninstalled ones. The call throws, `runCatching` swallows it, and the tile draws an empty box.
 *
 * That is not an edge case here. 凍結融解 keeps 54 apps frozen as their resting state, so a picker
 * over them — *"pick the apps to make unfreeze tasks for"* — is by definition a picker over apps the
 * plain lookup denies exists. 白い熊 caught it on 2026-09-13: a grid where Raiffeisen had a name and
 * no icon while RaiPay, thawed at that moment, had both.
 *
 * The label survives because the list already holds an `ApplicationInfo`; only the icon takes the
 * second lookup. So the fix is to do that lookup under [AppFreeze.MATCH_FROZEN] — the flags this app
 * already defines for "every label, icon and existence check that may run against a frozen app".
 */
object AppIcons {

    /**
     * The app's icon, readable whether it is running, suspended, disabled or hidden.
     *
     * Falls back to the plain lookup if the flagged one fails, and to null if both do — a package
     * that is genuinely gone (uninstalled while the grid was open) still draws an empty tile rather
     * than taking the dialog down.
     */
    fun load(context: Context, pkg: String): Drawable? {
        val pm = context.packageManager
        return runCatching { pm.getApplicationIcon(pm.getApplicationInfo(pkg, AppFreeze.MATCH_FROZEN)) }
            .recoverCatching { pm.getApplicationIcon(pkg) }
            .getOrNull()
    }
}
