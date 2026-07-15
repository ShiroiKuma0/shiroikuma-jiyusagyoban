package com.opentasker.core.scripting

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object TermuxScriptBackend {
    const val ACTION_ID = "script.termux.run"
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SCRIPT_DIRECTORY = "~/.termux/tasker"
    const val MINIMUM_RESULT_VERSION = "0.109"
    const val SETUP_URL = "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent"

    fun inspect(context: Context): TermuxScriptStatus {
        val versionName = packageVersionName(context, TERMUX_PACKAGE)
        val termuxInstalled = versionName != null
        return statusFor(
            termuxInstalled = termuxInstalled,
            permissionGranted = termuxInstalled &&
                context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED,
            resultProtocolSupported = versionName?.let(::supportsResultProtocol) == true,
        )
    }

    fun isDispatchReady(context: Context): Boolean {
        val status = inspect(context)
        return status.isReady
    }

    fun statusFor(
        termuxInstalled: Boolean,
        permissionGranted: Boolean,
        resultProtocolSupported: Boolean = true,
    ): TermuxScriptStatus {
        val state = when {
            !termuxInstalled -> TermuxScriptState.TermuxMissing
            !resultProtocolSupported -> TermuxScriptState.VersionUnsupported
            !permissionGranted -> TermuxScriptState.PermissionRequired
            else -> TermuxScriptState.Ready
        }
        val summary = when (state) {
            TermuxScriptState.TermuxMissing -> "Termux is not installed."
            TermuxScriptState.VersionUnsupported -> "Termux $MINIMUM_RESULT_VERSION or newer is required for bounded results."
            TermuxScriptState.PermissionRequired -> "Termux is installed, but OpenTasker lacks RUN_COMMAND permission."
            TermuxScriptState.Ready -> "Termux is installed and RUN_COMMAND permission is granted."
        }
        return TermuxScriptStatus(
            state = state,
            summary = summary,
        )
    }

    fun hintForAction(actionId: String): TermuxScriptHint? =
        if (actionId == ACTION_ID) {
            TermuxScriptHint(
                actionId = actionId,
                message = "Requires Termux 0.109 or newer with RUN_COMMAND permission.",
            )
        } else {
            null
        }

    internal fun supportsResultProtocol(versionName: String): Boolean {
        val match = Regex("^(\\d+)\\.(\\d+)").find(versionName.trim()) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 0 || (major == 0 && minor >= 109)
    }

    private fun packageVersionName(context: Context, packageName: String): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()?.versionName
}

data class TermuxScriptStatus(
    val state: TermuxScriptState,
    val summary: String,
) {
    val termuxInstalled: Boolean
        get() = state != TermuxScriptState.TermuxMissing

    val isReady: Boolean
        get() = state == TermuxScriptState.Ready
}

enum class TermuxScriptState {
    TermuxMissing,
    VersionUnsupported,
    PermissionRequired,
    Ready,
}

data class TermuxScriptHint(
    val actionId: String,
    val message: String,
)
