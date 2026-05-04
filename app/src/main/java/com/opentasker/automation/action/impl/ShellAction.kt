package com.opentasker.automation.action.impl

import com.opentasker.automation.core.ActionDefinition
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
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

            val timeout = ((config.config["timeout"] as Number?)?.toLong() ?: 30L).coerceIn(1L, MAX_TIMEOUT_SECONDS)
            val validationError = validateCommand(command)
            if (validationError != null) {
                return ActionResult(
                    success = false,
                    message = validationError,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }

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
            val parts = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val process = ProcessBuilder(parts)
                .redirectErrorStream(false)
                .start()

            // Wait for process with timeout
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
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

    private fun validateCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return "Command cannot be empty"
        if (trimmed.length > MAX_COMMAND_LENGTH) return "Command exceeds $MAX_COMMAND_LENGTH characters"
        if (FORBIDDEN_TOKENS.any { trimmed.contains(it) }) {
            return "Shell control operators are not allowed"
        }
        val executable = trimmed.substringBefore(' ')
        if (executable !in ALLOWED_COMMANDS) {
            return "Command '$executable' is not in the safe command allowlist"
        }
        if (!trimmed.matches(SAFE_COMMAND_PATTERN)) {
            return "Command contains unsupported characters"
        }
        return null
    }

    companion object {
        private const val MAX_TIMEOUT_SECONDS = 30L
        private const val MAX_COMMAND_LENGTH = 512
        private val ALLOWED_COMMANDS = setOf("echo", "getprop", "id", "logcat", "ping", "pm", "settings")
        private val FORBIDDEN_TOKENS = listOf(";", "&&", "||", "|", "`", "$(", ">", "<", "\n", "\r")
        private val SAFE_COMMAND_PATTERN = Regex("^[A-Za-z0-9_./:=@,%+\\-\\s]+$")
    }
}
