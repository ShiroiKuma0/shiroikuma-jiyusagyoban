package com.opentasker.core.policy

import android.content.Context
import android.content.pm.PackageManager
import com.opentasker.core.shizuku.ShizukuShell

/**
 * Freezing an app, and the four independent gates that word covers.
 *
 * | gate | written by | cleared by |
 * | --- | --- | --- |
 * | stopped | `am force-stop` over Shizuku | the next thing that starts the app |
 * | enabled-state | `pm disable-user` | `pm enable` |
 * | `com.android.shell` suspension | a shell-side `pm suspend` | `pm unsuspend` |
 * | `android` suspension | [DevicePolicyBridge.setSuspended], filed under 雫's admin | only the owner or a `DELEGATION_PACKAGE_ACCESS` delegate |
 * | hidden | [DevicePolicyBridge.setHidden], filed under the same admin | the same — and nothing else |
 *
 * `PackageUserState.suspendParams` is a **map keyed by the suspending package**, and the app stays
 * suspended while any entry survives. That is the whole reason this file exists: on 2026-09-05 a
 * defrost task ran `pm enable` over five apps suspended under the owner's admin, exited 0, logged
 * "Unfroze …" and changed nothing — and the launch that followed drew Android's
 * `ShowAdminSupportDetailsDialog` instead of the app.
 *
 * **The hidden gate arrived on 2026-09-10**, when 白い熊 応用管理 +29 made "Total freeze" its default
 * and started setting all of them at once. Hiding is the one gate that also changes what the phone
 * will *admit* about a package: `getApplicationInfo` without [MATCH_FROZEN] throws
 * `NameNotFoundException`, `pm list packages` omits it, and `getLaunchIntentForPackage` answers null.
 * So a hidden app does not merely read as frozen — it reads as **not installed**, which is the answer
 * every pre-flight in this app used to take at face value and skip its thaw on.
 *
 * So [thaw] clears **every** gate, unconditionally, in order, and then re-reads. It never branches on
 * how the app was frozen, because it cannot know: with both 応用管理 and this app writing under one
 * admin, whatever either froze the other must be able to lift.
 */
object AppFreeze {

    /**
     * The PackageManager flags under which a frozen app is readable at all.
     *
     * `MATCH_DISABLED_COMPONENTS` for the `pm disable-user` gate, `MATCH_UNINSTALLED_PACKAGES` for the
     * hidden one — `PackageUserState.isAvailable` returns a hidden package only when the caller asks
     * for uninstalled ones. Every label, icon and existence check that may run against a frozen app
     * uses this; with plain flags they all report an app that is sitting right there as gone.
     */
    const val MATCH_FROZEN: Int =
        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES

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

    /**
     * How an app is currently held. Empty means it is running normally.
     *
     * [installed] is "present for this user", not "the platform has heard of it" — see [read] for the
     * difference and why conflating the two would be the worse half of this bug. [remembered] marks
     * the case that makes them differ: a package whose `PackageSetting` survives an
     * uninstall-for-user-0, which a lookup for uninstalled packages still answers for.
     */
    data class State(
        val disabled: Boolean,
        val suspended: Boolean,
        val hidden: Boolean,
        val installed: Boolean,
        val remembered: Boolean = false,
    ) {
        val frozen: Boolean get() = installed && (disabled || suspended || hidden)

        /** The gates that are set, for a log line — "hidden+suspended", or "" when nothing holds it. */
        val summary: String
            get() = listOfNotNull(
                "hidden".takeIf { hidden },
                "suspended".takeIf { suspended },
                "disabled".takeIf { disabled },
            ).joinToString("+")
    }

    /**
     * Read the freeze state with no privilege at all — no Shizuku, no delegation.
     *
     * Suspension is a public flag (`FLAG_SUSPENDED`, API 24), so this stays honest on a phone with
     * neither. Reading only the enabled state, as this app did until 2026-09-05, reports a suspended
     * app as running: every caller that thaws-does-work-refreezes then skipped the thaw and sat out
     * its whole reply timeout against an app that could not receive the broadcast.
     *
     * **Hidden is read by difference**, not by asking: the platform exposes no public flag for it, but
     * it does hide the package from a plain lookup and reveal it under [MATCH_FROZEN]. That difference
     * needs no permission, which matters because the delegation that *can* answer
     * (`isApplicationHidden`) is exactly what a phone in trouble is missing.
     *
     * **The difference alone is not enough, and getting that wrong is the worse half of this bug.**
     * `MATCH_UNINSTALLED_PACKAGES` also answers for a package that is merely *remembered* — a system
     * app uninstalled for user 0 keeps its `PackageSetting` (`installed=false hidden=false`), and 102
     * of them sit on this phone. Calling those hidden would report an app that is not there as frozen:
     * a thaw-work-refreeze would work on a ghost, and the share relay's dead entries would become
     * immortal instead of being cleaned up — the same bug with the sign flipped (応用管理, 2026-09-10).
     * So the flag the platform *does* expose decides: `FLAG_INSTALLED` is set for a hidden app and
     * clear for a remembered one, and it is consulted only on the branch where the plain lookup already
     * failed, so a normal app can never be talked out of existing by it.
     *
     * `installed` therefore means "present for this user". A hidden app is installed and held, not
     * gone: callers must branch on [State.frozen] and thaw, not read `installed` as permission to give
     * up — that mistake is why a totally frozen share target used to be dropped from the relay store as
     * "no longer installed".
     */
    fun read(context: Context, pkg: String): State {
        val pm = context.applicationContext.packageManager
        val visible = runCatching {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull()
        val info = visible ?: run {
            val known = runCatching { pm.getApplicationInfo(pkg, MATCH_FROZEN) }.getOrNull()
                ?: return ABSENT
            // Invisible and remembered rather than invisible and hidden: nothing to freeze, nothing to
            // thaw, and nothing to talk to.
            if ((known.flags and FLAG_INSTALLED) == 0) return ABSENT.copy(remembered = true)
            known
        }
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
        val hidden = visible == null || DevicePolicyBridge.isHidden(context, pkg)
        return State(disabled = disabled, suspended = suspended, hidden = hidden, installed = true)
    }

    /** Not here: either never installed, or remembered from an uninstall and no longer present. */
    private val ABSENT = State(disabled = false, suspended = false, hidden = false, installed = false)

    /** One gate of a freeze, in the order [freeze] applies them. */
    enum class Gate(val label: String) {
        STOP("stopped"),
        SUSPEND("suspended"),
        DISABLE("disabled"),
        HIDE("hidden"),
    }

    /** What [freeze] managed to apply, and whether the app ended up held at all. */
    data class FreezeResult(val gates: List<Gate>, val frozen: Boolean) {
        /** For the log line: "stopped+suspended+disabled+hidden", or "nothing". */
        val summary: String
            get() = if (gates.isEmpty()) "nothing" else gates.joinToString("+") { it.label }
    }

    /**
     * Freeze [pkg] as hard as this phone allows — the same **four gates** 応用管理's "Total freeze"
     * applies, in the same order, so an app re-frozen from a bubble comes back exactly as held as it
     * was before a task thawed it.
     *
     * Applying fewer would be worse than it sounds: [thaw] clears all four, so a freeze that only
     * suspended would quietly *downgrade* every app that passes through a launcher task, and 応用管理
     * would go on listing it as frozen by a route it never applied.
     *
     * No gate short-circuits the next and each is best-effort: the policy pair needs the delegation,
     * the shell pair needs Shizuku, and a phone missing one still deserves whatever the other can do.
     * The verdict comes from a fresh [read], never from what the writes returned.
     *
     * **A PERSISTENT system app is not stopped by any of this** — `ActivityManagerService` spares
     * persistent processes from force-stop by design, so the gates keep it from being *started* again
     * and nothing more. `com.huawei.hiview` was measured still running through all four (応用管理,
     * 2026-09-10); that is the platform, not a failure here.
     */
    fun freeze(context: Context, pkg: String): FreezeResult {
        if (protectedReason(pkg) != null) return FreezeResult(emptyList(), frozen = false)
        val shell = runCatching { ShizukuShell.available() }.getOrDefault(false)
        val policy = DevicePolicyBridge.canManagePackages(context)
        val applied = mutableListOf<Gate>()
        // 1. evict what is running now; the other three only govern what may start.
        if (shell && shellSucceeded("am force-stop $pkg")) applied += Gate.STOP
        // 2. the owner's suspension — the gate no shell command can lift.
        if (policy && DevicePolicyBridge.setSuspended(context, pkg, true)) applied += Gate.SUSPEND
        // 3. the enabled state, which is also the whole freeze on a phone with no delegation.
        if (shell && shellSucceeded("pm disable-user --user 0 $pkg")) applied += Gate.DISABLE
        // 4. hidden goes LAST: once it is set the package reads as not installed, and a gate written
        //    after it would be arguing with a lookup that can no longer see the app.
        if (policy && DevicePolicyBridge.setHidden(context, pkg, true)) applied += Gate.HIDE
        return FreezeResult(applied, frozen = read(context, pkg).frozen)
    }

    /**
     * Clear every gate on [pkg] and report whether it is actually free afterwards.
     *
     * Each step is best-effort and **none short-circuits the next**: the gates are independent, and a
     * step that fails because its gate was never set looks exactly like one that fails because it
     * could not be lifted. On a phone with no Device Owner, steps 1 and 3 are two cheap refusals.
     *
     * **Unhide first.** While the package is hidden every later call argues with a lookup that says it
     * is not installed — this is the order 応用管理's own `FreezeUtils.unfreeze` uses, and getting it
     * wrong is the bug their +29 had to fix: a read that could not see hidden packages skipped the
     * reveal and stranded the app.
     */
    fun thaw(context: Context, pkg: String): Boolean {
        // 1. the hidden gate, before anything has to find the package
        runCatching { DevicePolicyBridge.setHidden(context, pkg, false) }
        // 2. the shell's own suspension slot
        runCatching {
            if (ShizukuShell.available()) ShizukuShell.exec("pm unsuspend $pkg")
        }
        // 3. the owner's slot — the one no shell command can reach
        runCatching { DevicePolicyBridge.setSuspended(context, pkg, false) }
        // 4. the enabled-state slot
        runCatching {
            if (ShizukuShell.available()) ShizukuShell.exec("pm enable $pkg")
        }
        // 5. the only answer that counts: is it thawed now?
        return !read(context, pkg).frozen
    }

    /**
     * Why [pkg] is still held after a [thaw], phrased so the run log names the thing to go and fix.
     *
     * A failed defrost has exactly two interesting causes and they need opposite actions: a policy
     * gate we are not a delegate for (grant "Device policy powers" in 雫) or an enabled-state gate
     * with no shell to lift it (start Shizuku). "A lock is still held" sent whoever read it to the
     * wrong one about half the time.
     */
    fun stuckReason(context: Context, pkg: String): String {
        val state = read(context, pkg)
        if (state.remembered) return "$pkg is not installed for this user — only its data is remembered"
        if (!state.installed) return "$pkg is not installed"
        if (!state.frozen) return "$pkg is not frozen"
        val policy = DevicePolicyBridge.canManagePackages(context)
        val shell = runCatching { ShizukuShell.available() }.getOrDefault(false)
        return when {
            (state.hidden || state.suspended) && !policy ->
                "$pkg is held by device policy (${state.summary}) and this app is not a " +
                    "DELEGATION_PACKAGE_ACCESS delegate — grant 自由作業盤 the device-policy powers in 白い熊 雫"
            state.disabled && !shell ->
                "$pkg is disabled and Shizuku is not available to run pm enable"
            else -> "$pkg is still held (${state.summary})"
        }
    }

    /** `sh -c <command>` through Shizuku, true only on a clean exit; never throws. */
    private fun shellSucceeded(command: String): Boolean =
        runCatching { ShizukuShell.exec(command).exitCode == 0 }.getOrDefault(false)

    /** `ApplicationInfo.FLAG_SUSPENDED` — public since API 24, but not a named constant there. */
    private const val FLAG_SUSPENDED = 1 shl 30

    /**
     * `ApplicationInfo.FLAG_INSTALLED` — `@hide`, but the bit is fixed platform ABI and the only
     * privilege-free way to tell a hidden package from a remembered one. Read exclusively on the
     * branch where a plain lookup already failed, so an OEM that failed to set it could at worst
     * report a hidden app as absent — the behaviour this fork had before 2026-09-10 — and could never
     * make a visible app vanish.
     */
    private const val FLAG_INSTALLED = 1 shl 23
}
