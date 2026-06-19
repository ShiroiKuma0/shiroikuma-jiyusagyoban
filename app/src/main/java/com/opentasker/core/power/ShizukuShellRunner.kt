package com.opentasker.core.power

import com.opentasker.core.logging.AppLogger

object ShizukuShellRunner {
    private const val TAG = "ShizukuShellRunner"

    private val COMMAND_ALLOWLIST: Map<String, List<List<String>>> = mapOf(
        "airplane.toggle" to listOf(
            listOf("settings", "put", "global", "airplane_mode_on", "1"),
            listOf("settings", "put", "global", "airplane_mode_on", "0"),
        ),
        "mobile.toggle" to listOf(
            listOf("svc", "data", "enable"),
            listOf("svc", "data", "disable"),
        ),
        "screenshot.take" to listOf(
            listOf("screencap", "-p"),
        ),
        "reboot" to listOf(
            listOf("svc", "power", "reboot", "false"),
        ),
        "screen.off" to listOf(
            listOf("input", "keyevent", "26"),
        ),
        "wake" to listOf(
            listOf("input", "keyevent", "224"),
        ),
    )

    @Volatile
    var processFactory: ShizukuProcessFactory = DefaultShizukuProcessFactory

    fun execute(actionId: String, variantIndex: Int = 0): ShellResult {
        val variants = COMMAND_ALLOWLIST[actionId]
            ?: return ShellResult.Failure("Action '$actionId' is not in the Shizuku allowlist")

        val command = variants.getOrNull(variantIndex)
            ?: return ShellResult.Failure("Invalid variant index $variantIndex for action '$actionId'")

        if (ShizukuPowerBackend.killSwitchEnabled) {
            return ShellResult.Failure("Shizuku kill-switch is active")
        }

        if (!ShizukuPowerBackend.isReady()) {
            return ShellResult.Failure("Shizuku is not ready")
        }

        return executeAllowedCommand(command, actionId)
    }

    fun isAllowed(actionId: String): Boolean = actionId in COMMAND_ALLOWLIST

    fun allowedVariantCount(actionId: String): Int =
        COMMAND_ALLOWLIST[actionId]?.size ?: 0

    private fun executeAllowedCommand(command: List<String>, actionId: String): ShellResult {
        AppLogger.info(TAG, "Executing: $actionId -> ${command.joinToString(" ")}")
        return try {
            val result = processFactory.execute(command)
            AppLogger.info(TAG, "Exit code ${result.exitCode} for $actionId")
            if (result.exitCode == 0) {
                ShellResult.Success(result.stdout.trim(), result.exitCode)
            } else {
                ShellResult.Failure("Exit code ${result.exitCode}: ${result.stderr.trim().ifEmpty { result.stdout.trim() }}")
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Shizuku command failed: ${e.message}", e)
            ShellResult.Failure("Shizuku error: ${e.message}")
        }
    }
}

interface ShizukuProcessFactory {
    fun execute(command: List<String>): ProcessOutput
}

data class ProcessOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

private object DefaultShizukuProcessFactory : ShizukuProcessFactory {
    override fun execute(command: List<String>): ProcessOutput {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            throw java.io.IOException("Command timed out after 10s")
        }
        return ProcessOutput(stdout, stderr, process.exitValue())
    }
}

sealed interface ShellResult {
    data class Success(val output: String, val exitCode: Int) : ShellResult
    data class Failure(val reason: String) : ShellResult
}
