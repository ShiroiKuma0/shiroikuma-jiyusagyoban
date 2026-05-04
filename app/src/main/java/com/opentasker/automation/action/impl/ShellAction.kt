package com.opentasker.automation.action.impl

import com.opentasker.automation.core.ActionDefinition
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell action that executes system commands.
 * Supports bash/sh command execution with timeout.
 */
class ShellAction : ActionDefinition {
    override val id = "shell"
    override val displayName = "Execute Command"

    override suspend fun execute(config: ActionConfig): ActionResult {
        return try {
            val startTime = System.currentTimeMillis()
            val command = config.config["command"] as String?
                ?: return ActionResult(
                    success = false,
                    message = "No command specified",
                    executionTimeMs = 0
                )

            val timeout = (config.config["timeout"] as Number?)?.toLong() ?: 30L

            val result = executeCommand(command, timeout)

            ActionResult(
                success = result.exitCode == 0,
                message = result.output ?: "Command executed with exit code ${result.exitCode}",
                executionTimeMs = System.currentTimeMillis() - startTime,
                details = mapOf(
                    "exitCode" to result.exitCode,
                    "output" to (result.output ?: ""),
                    "error" to (result.error ?: "")
                )
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "Command execution failed: ${e.message}",
                executionTimeMs = 0,
                stackTrace = e.stackTraceToString()
            )
        }
    }

    private fun executeCommand(command: String, timeoutSeconds: Long): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            // Wait for process with timeout
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroy()
                return CommandResult(
                    exitCode = -1,
                    output = null,
                    error = "Command execution timeout after $timeoutSeconds seconds"
                )
            }

            // Capture output
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }

            CommandResult(
                exitCode = process.exitValue(),
                output = output.takeIf { it.isNotBlank() },
                error = error.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                output = null,
                error = e.message
            )
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String?,
        val error: String?
    )
}
