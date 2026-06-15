package com.opentasker.core.power

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object ShizukuPowerBackend {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val SETUP_URL = "https://shizuku.rikka.app/guide/setup/"

    val elevatedActionIds: Set<String> = setOf(
        "airplane.toggle",
        "mobile.toggle",
        "screenshot.take",
        "reboot",
        "screen.off",
        "wake",
    )

    fun inspect(context: Context): ShizukuPowerStatus =
        statusFor(managerInstalled = isPackageInstalled(context, MANAGER_PACKAGE))

    fun statusFor(managerInstalled: Boolean): ShizukuPowerStatus =
        if (managerInstalled) {
            ShizukuPowerStatus(
                state = ShizukuPowerState.ManagerInstalled,
                summary = "Shizuku manager is installed. 白い熊 自由作業盤 has not linked the Shizuku API yet.",
            )
        } else {
            ShizukuPowerStatus(
                state = ShizukuPowerState.NotInstalled,
                summary = "Shizuku manager is not installed.",
            )
        }

    fun hintForAction(actionId: String): ShizukuActionHint? =
        if (actionId in elevatedActionIds) {
            ShizukuActionHint(
                actionId = actionId,
                message = "Candidate for optional Shizuku support; blocked until the elevated backend is implemented and explicitly enabled.",
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

data class ShizukuPowerStatus(
    val state: ShizukuPowerState,
    val summary: String,
) {
    val managerInstalled: Boolean
        get() = state == ShizukuPowerState.ManagerInstalled
}

enum class ShizukuPowerState {
    NotInstalled,
    ManagerInstalled,
}

data class ShizukuActionHint(
    val actionId: String,
    val message: String,
)
