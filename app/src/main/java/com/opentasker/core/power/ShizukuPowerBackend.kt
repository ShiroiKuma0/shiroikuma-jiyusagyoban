package com.opentasker.core.power

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.opentasker.core.logging.AppLogger
import rikka.shizuku.Shizuku

object ShizukuPowerBackend {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
    private const val TAG = "ShizukuPowerBackend"

    val elevatedActionIds: Set<String> = setOf(
        "airplane.toggle",
        "mobile.toggle",
        "screenshot.take",
        "reboot",
        "screen.off",
        "wake",
    )

    private val ALLOWED_COMMANDS: Map<String, List<String>> = mapOf(
        "airplane.toggle" to listOf("settings", "put", "global", "airplane_mode_on"),
        "mobile.toggle" to listOf("svc", "data"),
        "screenshot.take" to listOf("screencap"),
        "reboot" to listOf("svc", "power", "reboot"),
        "screen.off" to listOf("input", "keyevent", "KEYCODE_POWER"),
        "wake" to listOf("input", "keyevent", "KEYCODE_WAKEUP"),
    )

    @Volatile
    var killSwitchEnabled: Boolean = false

    fun inspect(context: Context): ShizukuPowerStatus {
        val installed = isPackageInstalled(context, MANAGER_PACKAGE)
        if (!installed) {
            return ShizukuPowerStatus(
                state = ShizukuPowerState.NotInstalled,
                summary = "Shizuku manager is not installed.",
            )
        }
        if (killSwitchEnabled) {
            return ShizukuPowerStatus(
                state = ShizukuPowerState.Disabled,
                summary = "Shizuku power mode is disabled by kill-switch.",
            )
        }
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return ShizukuPowerStatus(
                state = ShizukuPowerState.ManagerInstalled,
                summary = "Shizuku manager is installed but the service is not running.",
            )
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) {
            ShizukuPowerStatus(
                state = ShizukuPowerState.Ready,
                summary = "Shizuku is active and permission is granted.",
            )
        } else {
            ShizukuPowerStatus(
                state = ShizukuPowerState.PermissionNeeded,
                summary = "Shizuku is running but OpenTasker needs permission.",
            )
        }
    }

    fun statusFor(managerInstalled: Boolean): ShizukuPowerStatus =
        if (managerInstalled) {
            ShizukuPowerStatus(
                state = ShizukuPowerState.ManagerInstalled,
                summary = "Shizuku manager is installed.",
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
                message = "Requires Shizuku elevated mode. Enable in Setup if the Shizuku manager is running.",
            )
        } else {
            null
        }

    fun isReady(): Boolean =
        !killSwitchEnabled &&
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
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
        get() = state != ShizukuPowerState.NotInstalled

    val isReady: Boolean
        get() = state == ShizukuPowerState.Ready
}

enum class ShizukuPowerState {
    NotInstalled,
    ManagerInstalled,
    PermissionNeeded,
    Ready,
    Disabled,
}

data class ShizukuActionHint(
    val actionId: String,
    val message: String,
)
