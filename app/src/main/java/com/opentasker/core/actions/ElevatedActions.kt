package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Step 3b — actions that drive privileged operations through the Shizuku shell. Each first checks
// Shizuku is usable (prompting for access once) then runs a single shell command.
// ---------------------------------------------------------------------------------------------

/** Returns a guidance Failure if Shizuku can't run a command right now (and prompts for access once). */
internal fun requireShizuku(feature: String): ActionResult.Failure? {
    if (ShizukuShell.available()) return null
    if (ShizukuShell.isRunning() && !ShizukuShell.hasPermission()) ShizukuShell.requestPermission()
    return ActionResult.Failure(
        if (!ShizukuShell.isRunning()) "$feature needs Shizuku — install and start Shizuku, then grant access."
        else "$feature needs Shizuku access — grant it in the dialog, then retry.",
    )
}

/** Run a single elevated command; Success on exit 0, otherwise a Failure carrying stderr. */
internal suspend fun runElevated(ctx: ActionContext, feature: String, command: String): ActionResult {
    requireShizuku(feature)?.let { return it }
    val result = runCatching { withContext(Dispatchers.IO) { ShizukuShell.exec(command) } }
        .getOrElse { return ActionResult.Failure("Shizuku exec failed: ${it.message}") }
    return if (result.exitCode == 0) {
        ctx.logger("$feature: ok")
        ActionResult.Success
    } else {
        ActionResult.Failure("$feature failed: ${result.stderr.trim().take(160).ifBlank { "exit ${result.exitCode}" }}")
    }
}

/** `Location Mode` (Tasker 905) — turn location services on/off via Shizuku. */
class LocationModeAction : Action {
    override val id = "location.mode"
    override val category = ActionCategory.SETTINGS
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val enabled = when (args["state"]?.trim()?.lowercase()) {
            "on", "enable", "high", "true", "1" -> true
            "off", "disable", "false", "0" -> false
            "toggle", null, "" -> {
                val current = runCatching {
                    android.provider.Settings.Secure.getInt(ctx.app.contentResolver, android.provider.Settings.Secure.LOCATION_MODE)
                }.getOrDefault(0)
                current == 0
            }
            else -> return ActionResult.Failure("state must be on / off / toggle")
        }
        return runElevated(ctx, "Location mode", "cmd location set-location-enabled $enabled")
    }
}

/** `Set Keyboard` (Tasker 469) — switch the active input method (IME) via Shizuku. */
class SetImeAction : Action {
    override val id = "ime.set"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ime = args["ime"]?.trim().orEmpty()
        if (ime.isEmpty()) return ActionResult.Failure("missing IME id (e.g. com.pkg/.ServiceName)")
        return runElevated(ctx, "Set keyboard", "ime enable '$ime' ; ime set '$ime'")
    }
}
