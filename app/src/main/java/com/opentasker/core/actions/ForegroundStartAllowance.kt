package com.opentasker.core.actions

import com.opentasker.core.shizuku.ShizukuShell

/**
 * Put the target on the temporary power allowlist so its foreground service may start from our
 * background call.
 *
 * A broadcast is a background start, so a sister app answering one with `startForegroundService` is
 * refused unless it has a recent foreground allowance — see [BackupDoorExportAction] for the
 * measurement. Shared by the backup door and the 音声 render ([OnseRenderAction]).
 *
 * Returns null when the allowance was granted, otherwise a line for the run log saying why not.
 */
internal fun grantTempAllowance(pkg: String, durationMs: Long): String? {
    if (!ShizukuShell.available()) {
        return "Shizuku unavailable — no foreground-start allowance for $pkg; a refusal is likely " +
            "unless it was recently open"
    }
    val result = runCatching {
        ShizukuShell.exec("cmd deviceidle tempwhitelist -d $durationMs $pkg")
    }.getOrNull() ?: return "could not grant $pkg a temporary foreground-start allowance"
    return if (result.exitCode == 0) null
    else "tempwhitelist refused for $pkg: ${result.stderr.trim().take(120)}"
}
