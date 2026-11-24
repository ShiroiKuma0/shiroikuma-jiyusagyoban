package com.opentasker.core.shizuku

import com.opentasker.i18n.tr
import java.util.concurrent.TimeUnit

/**
 * One shell command with more rights than the app has: through Shizuku (adb's rights) when it is
 * running and has granted access, otherwise through root (`su`). Used by the Setup tab for grants
 * Android offers no dialog and no settings page for. Blocking — call it off the main thread.
 */
object PrivilegedShell {
    /** [route] is "Shizuku", "root" or "" when neither was available; [detail] is for 白い熊 to read. */
    data class Outcome(val ok: Boolean, val route: String, val detail: String)

    private const val SU_TIMEOUT_S = 30L

    fun run(command: String): Outcome {
        val problems = mutableListOf<String>()
        when {
            ShizukuShell.available() -> {
                val result = runCatching { ShizukuShell.exec(command) }
                val shell = result.getOrNull()
                if (shell != null && shell.exitCode == 0) return Outcome(true, "Shizuku", shell.stdout.trim())
                problems += "Shizuku: " + (shell?.let { it.stderr.trim().ifEmpty { "exit ${it.exitCode}" } }
                    ?: result.exceptionOrNull()?.message ?: "failed")
            }
            ShizukuShell.isRunning() -> {
                ShizukuShell.requestPermission()
                problems += tr("Shizuku is running but has not granted access — allow it and tap again")
            }
            else -> problems += tr("Shizuku is not running")
        }
        val root = runCatching { viaRoot(command) }
        root.getOrNull()?.let { (exit, err) ->
            if (exit == 0) return Outcome(true, "root", "")
            problems += "root: " + err.ifEmpty { "exit $exit" }
        } ?: run { problems += tr("no root (su) on this phone") }
        return Outcome(false, "", problems.joinToString("; "))
    }

    /** Exit code and stderr of `su -c <command>`; throws when there is no `su` binary. */
    private fun viaRoot(command: String): Pair<Int, String> {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.outputStream.close()
        // A root manager may show its own prompt first, so wait for it — but never for ever.
        if (!process.waitFor(SU_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            return -1 to tr("no answer from su in %1\$s s", SU_TIMEOUT_S)
        }
        val err = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
        return process.exitValue() to err.trim().take(200)
    }
}
