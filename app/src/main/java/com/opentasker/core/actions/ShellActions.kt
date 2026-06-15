package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `Run Shell` (Tasker 123) — run a shell command with Shizuku privileges; capture stdout/stderr/exit. */
class ShellRunAction : Action {
    override val id = "shell.run"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return ActionResult.Failure("missing command")
        if (!ShizukuShell.isRunning()) {
            return ActionResult.Failure("Shizuku is not running — install and start Shizuku, then grant access.")
        }
        if (!ShizukuShell.hasPermission()) {
            ShizukuShell.requestPermission()
            return ActionResult.Failure("Requested Shizuku access — grant it in the dialog, then run again.")
        }
        val result = try {
            withContext(Dispatchers.IO) { ShizukuShell.exec(command) }
        } catch (e: Exception) {
            return ActionResult.Failure("Shizuku exec failed: ${e.message}")
        }
        ctx.variables.set(args["store_stdout"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stdout", result.stdout.trim())
        ctx.variables.set(args["store_stderr"]?.trim()?.takeIf { it.isNotEmpty() } ?: "stderr", result.stderr.trim())
        ctx.variables.set(args["store_exit"]?.trim()?.takeIf { it.isNotEmpty() } ?: "exit", result.exitCode.toString())
        ctx.logger("Run Shell exit ${result.exitCode}")
        return if (result.exitCode == 0 || truthy(args["ignore_exit"])) {
            ActionResult.Success
        } else {
            ActionResult.Failure("shell exited ${result.exitCode}: ${result.stderr.trim().take(200)}")
        }
    }
}
