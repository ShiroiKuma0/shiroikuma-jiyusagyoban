package com.opentasker.core.scripting

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object TermuxScriptBackend {
    const val ACTION_ID = "script.termux.run"
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_TASKER_PACKAGE = "com.termux.tasker"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SCRIPT_DIRECTORY = "~/.termux/tasker"
    const val SETUP_URL = "https://github.com/termux/termux-tasker/blob/master/README.md#setup-instructions"

    fun inspect(context: Context): TermuxScriptStatus =
        statusFor(
            termuxInstalled = isPackageInstalled(context, TERMUX_PACKAGE),
            taskerPluginInstalled = isPackageInstalled(context, TERMUX_TASKER_PACKAGE),
        )

    fun statusFor(termuxInstalled: Boolean, taskerPluginInstalled: Boolean): TermuxScriptStatus {
        val state = when {
            !termuxInstalled -> TermuxScriptState.TermuxMissing
            !taskerPluginInstalled -> TermuxScriptState.TaskerPluginMissing
            else -> TermuxScriptState.PluginInstalled
        }
        val summary = when (state) {
            TermuxScriptState.TermuxMissing -> "Termux is not installed."
            TermuxScriptState.TaskerPluginMissing -> "Termux is installed, but Termux:Tasker is not installed."
            TermuxScriptState.PluginInstalled -> "Termux and Termux:Tasker are installed. 白い熊 自由作業盤 has not enabled script dispatch yet."
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
                message = "Candidate for optional Termux:Tasker support; blocked until script dispatch, permission handling, output capture, and run-log auditing are implemented.",
            )
        } else {
            null
        }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
}

data class TermuxScriptStatus(
    val state: TermuxScriptState,
    val summary: String,
) {
    val bridgeInstalled: Boolean
        get() = state == TermuxScriptState.PluginInstalled
}

enum class TermuxScriptState {
    TermuxMissing,
    TaskerPluginMissing,
    PluginInstalled,
}

data class TermuxScriptHint(
    val actionId: String,
    val message: String,
)
