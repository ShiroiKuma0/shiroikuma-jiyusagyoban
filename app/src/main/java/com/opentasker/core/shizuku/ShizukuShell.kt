package com.opentasker.core.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Thin wrapper over the Shizuku API for running privileged (ADB-shell / root) commands. Shizuku must
 * be installed and started by the user; access is a runtime permission they grant once. The actual
 * shell process is obtained through Shizuku's hidden `newProcess` (reached by reflection so the build
 * doesn't depend on a non-public symbol).
 */
object ShizukuShell {
    const val PERMISSION_REQUEST_CODE = 4801

    /** Shizuku service running and its binder reachable. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** This app has been granted Shizuku access. */
    fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shizuku is running and access is granted — i.e. elevated commands can run right now. */
    fun available(): Boolean = isRunning() && hasPermission()

    /** Pop Shizuku's permission dialog (result is picked up on the next [hasPermission] check). */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)

    /** Run `sh -c <command>` through Shizuku and capture its output. Throws if exec isn't available. */
    fun exec(command: String): ShellResult {
        val process = newProcess(arrayOf("sh", "-c", command))
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        return ShellResult(stdout, stderr, exit)
    }

    private fun newProcess(cmd: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(null, cmd, null, null) as Process
    }
}
