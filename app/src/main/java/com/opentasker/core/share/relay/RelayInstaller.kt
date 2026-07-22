package com.opentasker.core.share.relay

import com.opentasker.core.shizuku.ShizukuShell
import java.io.File

/**
 * Installs / uninstalls generated relay APKs through Shizuku (`pm`, shell UID). Uses a **streaming
 * install session** — `pm install-create` → `pm install-write … -` (APK piped to the shell process's
 * stdin) → `pm install-commit` — so the shell UID never has to read our app-private files (which it
 * can't; `filesDir` is 0700). `pm uninstall` removes a relay on target removal.
 */
object RelayInstaller {

    sealed interface Result {
        data object Success : Result
        /** The installed relay is signed by a different key (our signing key rotated) — reinstall fresh. */
        data object SignatureMismatch : Result
        data class Failure(val message: String) : Result
    }

    fun install(apk: File): Result {
        if (!ShizukuShell.available()) return Result.Failure("Shizuku unavailable")
        val size = apk.length()

        val create = runCatching { ShizukuShell.exec("pm install-create -r -t") }
            .getOrElse { return Result.Failure("install-create: ${it.message}") }
        val sid = Regex("\\[(\\d+)\\]").find(create.stdout)?.groupValues?.get(1)
            ?: return Result.Failure("install-create failed: ${create.stdout}${create.stderr}".trim())

        val writeOk = runCatching {
            val proc = ShizukuShell.stream("pm install-write -S $size $sid base -")
            apk.inputStream().use { input -> proc.outputStream.use { out -> input.copyTo(out) } }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            (code == 0) to "$out$err"
        }.getOrElse { false to (it.message ?: "write error") }
        if (!writeOk.first) {
            runCatching { ShizukuShell.exec("pm install-abandon $sid") }
            return Result.Failure("install-write failed: ${writeOk.second}".trim())
        }

        val commit = runCatching { ShizukuShell.exec("pm install-commit $sid") }
            .getOrElse { return Result.Failure("install-commit: ${it.message}") }
        val text = "${commit.stdout}${commit.stderr}"
        return when {
            commit.stdout.contains("Success") -> Result.Success
            text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                text.contains("signatures do not match") -> Result.SignatureMismatch
            else -> Result.Failure("install failed: ${text.trim()}")
        }
    }

    fun uninstall(relayPackage: String): Result {
        if (!ShizukuShell.available()) return Result.Failure("Shizuku unavailable")
        val r = runCatching { ShizukuShell.exec("pm uninstall $relayPackage") }
            .getOrElse { return Result.Failure("uninstall: ${it.message}") }
        return if (r.stdout.contains("Success")) Result.Success
        else Result.Failure("uninstall failed: ${r.stdout}${r.stderr}".trim())
    }

    /** True if [relayPackage] is currently installed (so the screen can show accurate state). */
    fun isInstalled(context: android.content.Context, relayPackage: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(relayPackage, 0); true
    }.getOrDefault(false)
}
