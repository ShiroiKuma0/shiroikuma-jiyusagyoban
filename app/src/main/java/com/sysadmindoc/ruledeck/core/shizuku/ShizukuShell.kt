package com.sysadmindoc.ruledeck.core.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Thin wrapper over the Shizuku API for running privileged (ADB-shell / root) commands. Shizuku must
 * be installed and started by the user; access is a runtime permission they grant once. The actual
 * shell process is obtained through Shizuku's hidden `newProcess` (reached by reflection so the build
 * doesn't depend on a non-public symbol).
 *
 * **A rooted phone needs no Shizuku** (白い熊, 2026-11-28). Where Shizuku cannot run a command right
 * now and [RootAccess.usable] says root may be used, [isRunning], [hasPermission] and [available]
 * answer yes and [exec] / [stream] run the command through `su -c` — as root instead of as the
 * shell, which can do everything the shell can. Shizuku keeps the first turn wherever it works, so a
 * phone without root behaves exactly as before. The one exception is `Run Shell` in its default
 * mode ([execAsShell]): a task's own command line runs as root only where its step says “root”. [serviceRunning] and [serviceGranted] are the
 * answers about Shizuku itself, for the places that show its state.
 */
object ShizukuShell {
    const val PERMISSION_REQUEST_CODE = 4801

    /** Shizuku itself: its service is running and its binder reachable. */
    fun serviceRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku itself: this app has been granted access. */
    fun serviceGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun shizukuReady(): Boolean = serviceRunning() && serviceGranted()

    /** Commands go through root: Shizuku cannot take them right now, and root may be used. */
    fun viaRoot(): Boolean = !shizukuReady() && RootAccess.usable()

    /** Something can carry a command: Shizuku's service is up, or root stands in for it. */
    fun isRunning(): Boolean = serviceRunning() || RootAccess.usable()

    /** Access is granted — by Shizuku, or by root standing in for it. */
    fun hasPermission(): Boolean = shizukuReady() || RootAccess.usable()

    /** Elevated commands can run right now — through Shizuku, or through root on a rooted phone. */
    fun available(): Boolean = shizukuReady() || RootAccess.usable()

    /** Pop Shizuku's permission dialog (result is picked up on the next [serviceGranted] check). */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)

    /** Run `sh -c <command>` through Shizuku — or root in its place — and capture its output. Throws if exec isn't available. */
    fun exec(command: String): ShellResult = collect(newProcess(arrayOf("sh", "-c", command)))

    /**
     * As [exec], but through Shizuku only — as the Android shell user, never as root. For `Run Shell`
     * in its default mode: what a task's own command line runs as is the step's choice, not the phone's.
     */
    fun execAsShell(command: String): ShellResult = collect(newProcess(arrayOf("sh", "-c", command), rootAllowed = false))

    private fun collect(process: Process): ShellResult {
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        return ShellResult(stdout, stderr, exit)
    }

    /**
     * Spawn `sh -c <command>` through Shizuku and return the live [Process] WITHOUT draining it — for
     * long-running commands whose stdout is read incrementally (e.g. `getevent`). The caller owns the
     * process lifecycle and must `destroy()` it. Throws if exec isn't available.
     */
    fun stream(command: String): Process = newProcess(arrayOf("sh", "-c", command))

    private fun newProcess(cmd: Array<String>, rootAllowed: Boolean = true): Process {
        // `sh -c <command>` for Shizuku is `su -c <command>` for root.
        if (rootAllowed && viaRoot() && cmd.size == 3 && cmd[0] == "sh" && cmd[1] == "-c") {
            return ProcessBuilder("su", "-c", cmd[2]).start()
        }
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(null, cmd, null, null) as Process
    }
}
