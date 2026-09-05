package com.opentasker.core.policy

import android.content.Context
import android.content.pm.PackageManager
import com.opentasker.core.shizuku.ShizukuShell

/**
 * Freezing an app, and the three independent locks that word covers.
 *
 * | slot | written by | cleared by |
 * | --- | --- | --- |
 * | enabled-state | `pm disable-user` | `pm enable` |
 * | `com.android.shell` suspension | a shell-side `pm suspend` (白い熊 応用管理 over Shizuku) | `pm unsuspend` |
 * | `android` suspension | [DevicePolicyBridge.setSuspended], filed under 雫's admin | only the owner or a `DELEGATION_PACKAGE_ACCESS` delegate |
 *
 * `PackageUserState.suspendParams` is a **map keyed by the suspending package**, and the app stays
 * suspended while any entry survives. That is the whole reason this file exists: on 2026-09-05 a
 * defrost task ran `pm enable` over five apps suspended under the owner's admin, exited 0, logged
 * "Unfroze …" and changed nothing — and the launch that followed drew Android's
 * `ShowAdminSupportDetailsDialog` instead of the app.
 *
 * So [thaw] clears **every** slot, unconditionally, in order, and then re-reads. It never branches on
 * how the app was frozen, because it cannot know: with both 応用管理 and this app able to suspend
 * under one admin, whatever either froze the other must be able to lift.
 */
object AppFreeze {

    /**
     * The three packages that hold this phone's automation together, and must never be frozen by
     * anything here (白い熊, 2026-09-05).
     *
     * - `shiroikuma.shizuku` is the Device Owner: the platform refuses anyway
     *   (`SecurityException: Cannot disable a protected package`), but a refusal we make ourselves
     *   says why, in a log line that names the rule.
     * - `shiroikuma.oyokanri` is the other delegate — the interactive way back when a task leaves an
     *   app stuck.
     * - `shiroikuma.jiyusagyoban` is this app: freezing it stops the engine that would thaw it.
     *
     * This guards what THIS app can do. It does not ask the platform to block their uninstall — that
     * would be a `DELEGATION_BLOCK_UNINSTALL` lock filed under 雫's admin, outliving this app and
     * this delegation, and it is not something to set as a side effect.
     */
    val PROTECTED: Set<String> = setOf(
        "shiroikuma.shizuku",
        "shiroikuma.oyokanri",
        "shiroikuma.jiyusagyoban",
    )

    /** Why [pkg] may never be frozen, or null when it may. */
    fun protectedReason(pkg: String): String? = when (pkg) {
        "shiroikuma.shizuku" -> "白い熊 雫 is the Device Owner — freezing it would strand every policy lock on this phone"
        "shiroikuma.oyokanri" -> "白い熊 応用管理 is the other way to lift a hard freeze"
        "shiroikuma.jiyusagyoban" -> "this app runs the task that would thaw it"
        else -> null
    }

    /** How an app is currently held. Empty means it is running normally. */
    data class State(val disabled: Boolean, val suspended: Boolean, val installed: Boolean) {
        val frozen: Boolean get() = disabled || suspended
    }

    /**
     * Read the freeze state with no privilege at all — no Shizuku, no delegation.
     *
     * Suspension is a public flag (`FLAG_SUSPENDED`, API 24), so this stays honest on a phone with
     * neither. Reading only the enabled state, as this app did until 2026-09-05, reports a suspended
     * app as running: every caller that thaws-does-work-refreezes then skipped the thaw and sat out
     * its whole reply timeout against an app that could not receive the broadcast.
     *
     * A **hidden** app (`setApplicationHidden`) is not visible to `getApplicationInfo` at all and
     * surfaces here as not installed, which is the truthful answer for anything about to talk to it.
     */
    fun read(context: Context, pkg: String): State {
        val pm = context.applicationContext.packageManager
        val info = runCatching {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull() ?: return State(disabled = false, suspended = false, installed = false)
        val disabled = when (runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrNull()) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> true
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
            // DEFAULT (and an unreadable setting): fall back to the effective manifest flag.
            else -> !info.enabled
        }
        val suspended = (info.flags and FLAG_SUSPENDED) != 0 ||
            DevicePolicyBridge.isSuspended(context, pkg)
        return State(disabled = disabled, suspended = suspended, installed = true)
    }

    /** What [freeze] did, so the caller can say which lock it applied. */
    enum class FreezeMethod { POLICY, DISABLE, NONE }

    /**
     * Freeze [pkg] as hard as this phone allows: the owner's suspension when we are a delegate,
     * otherwise the old `pm disable-user`.
     *
     * The strong path needs no Shizuku — it is the platform's own API — so an app can be frozen on a
     * phone where the shell is unavailable, which the fallback could never do.
     */
    fun freeze(context: Context, pkg: String): FreezeMethod {
        if (protectedReason(pkg) != null) return FreezeMethod.NONE
        if (DevicePolicyBridge.canSuspend(context) && DevicePolicyBridge.setSuspended(context, pkg, true)) {
            return FreezeMethod.POLICY
        }
        val disabled = runCatching {
            ShizukuShell.available() && ShizukuShell.exec("pm disable-user --user 0 $pkg").exitCode == 0
        }.getOrDefault(false)
        return if (disabled) FreezeMethod.DISABLE else FreezeMethod.NONE
    }

    /**
     * Clear every lock on [pkg] and report whether it is actually free afterwards.
     *
     * Each step is best-effort and **none short-circuits the next**: the slots are independent, and a
     * step that fails because its lock was never set looks exactly like one that fails because it
     * could not be lifted. On a phone with no Device Owner, step 2 is one cheap refusal.
     */
    fun thaw(context: Context, pkg: String): Boolean {
        // 1. the shell's own suspension slot
        runCatching {
            if (ShizukuShell.available()) ShizukuShell.exec("pm unsuspend $pkg")
        }
        // 2. the owner's slot — the one no shell command can reach
        runCatching { DevicePolicyBridge.setSuspended(context, pkg, false) }
        // 3. the enabled-state slot
        runCatching {
            if (ShizukuShell.available()) ShizukuShell.exec("pm enable $pkg")
        }
        // 4. the only answer that counts: is it thawed now?
        return !read(context, pkg).frozen
    }

    /** `ApplicationInfo.FLAG_SUSPENDED` — public since API 24, but not a named constant there. */
    private const val FLAG_SUSPENDED = 1 shl 30
}
