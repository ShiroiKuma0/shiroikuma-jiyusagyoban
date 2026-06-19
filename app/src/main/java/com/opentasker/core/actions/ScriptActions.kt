package com.opentasker.core.actions

import android.content.ComponentName
import android.content.Intent
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scripting.TermuxScriptBackend
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class TermuxScriptAction : Action {
    override val id = TermuxScriptBackend.ACTION_ID
    override val category = ActionCategory.PLUGIN

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val executable = args["executable"]?.trim()
            ?: return ActionResult.Failure("Missing 'executable' argument")

        if (executable.isBlank()) {
            return ActionResult.Failure("Executable path is blank")
        }

        if (!TermuxScriptBackend.isDispatchReady(ctx.app)) {
            return ActionResult.Failure(
                "Termux dispatch is not ready: ${TermuxScriptBackend.inspect(ctx.app).summary}",
            )
        }

        if (!TermuxScriptDispatch.checkFrequencyCap(executable)) {
            return ActionResult.Failure("Script '$executable' is rate-limited. Wait before re-dispatching.")
        }

        val arguments = args["arguments"]?.split(" ")?.toTypedArray() ?: emptyArray()
        val workingDirectory = args["workingDirectory"]?.trim()?.ifBlank { null }
        val capturePrefix = args["capturePrefix"]?.trim()?.ifBlank { null }

        val intent = Intent().apply {
            component = ComponentName(
                TermuxScriptBackend.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService",
            )
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", executable)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            workingDirectory?.let {
                putExtra("com.termux.RUN_COMMAND_WORKDIR", it)
            }
        }

        return try {
            ctx.app.startForegroundService(intent)
            TermuxScriptDispatch.recordDispatch(executable)
            ctx.logger("Dispatched Termux script: $executable")

            if (capturePrefix != null) {
                ctx.variables.set("${capturePrefix}_dispatched", "true")
                ctx.variables.set("${capturePrefix}_executable", executable)
            }

            ActionResult.Success
        } catch (e: SecurityException) {
            ActionResult.Failure("Permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.error("TermuxScriptAction", "Dispatch failed: ${e.message}", e)
            ActionResult.Failure("Dispatch failed: ${e.message}")
        }
    }
}

object TermuxScriptDispatch {
    private const val MIN_DISPATCH_INTERVAL_MS = 1000L
    private val lastDispatchTimes = ConcurrentHashMap<String, Long>()

    fun checkFrequencyCap(executable: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = lastDispatchTimes[executable] ?: return true
        return (now - lastTime) >= MIN_DISPATCH_INTERVAL_MS
    }

    fun recordDispatch(executable: String) {
        lastDispatchTimes[executable] = System.currentTimeMillis()
    }

    fun hashScript(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }

    fun verifyScriptHash(scriptFile: File, expectedHash: String): Boolean {
        if (!scriptFile.exists()) return false
        val actualHash = hashScript(scriptFile.readBytes())
        return actualHash.equals(expectedHash, ignoreCase = true)
    }
}
