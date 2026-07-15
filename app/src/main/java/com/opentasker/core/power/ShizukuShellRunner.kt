package com.opentasker.core.power

/**
 * Command policy boundary for a future Shizuku user-service/Binder transport.
 *
 * This build deliberately has no command transport. In particular, an app-UID [ProcessBuilder]
 * must never be treated as Shizuku execution or satisfy backend readiness.
 */
object ShizukuShellRunner {
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

    fun execute(actionId: String, variantIndex: Int = 0): ShellResult {
        val variants = COMMAND_ALLOWLIST[actionId]
            ?: return ShellResult.Failure("Action '$actionId' is not in the Shizuku allowlist")
        if (variants.getOrNull(variantIndex) == null) {
            return ShellResult.Failure("Invalid variant index $variantIndex for action '$actionId'")
        }
        if (ShizukuPowerBackend.killSwitchEnabled) {
            return ShellResult.Failure("Shizuku kill switch is active")
        }
        return ShellResult.Failure(
            "No privileged Shizuku user-service transport is available; ordinary app processes are never used as a fallback",
        )
    }

    fun isAllowed(actionId: String): Boolean = actionId in COMMAND_ALLOWLIST

    fun allowedVariantCount(actionId: String): Int =
        COMMAND_ALLOWLIST[actionId]?.size ?: 0

    fun hasPrivilegedTransport(): Boolean = false
}

sealed interface ShellResult {
    data class Success(val output: String, val exitCode: Int) : ShellResult
    data class Failure(val reason: String) : ShellResult
}
