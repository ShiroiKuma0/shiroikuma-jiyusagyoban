package com.opentasker.core.power

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.opentasker.core.logging.AppLogger
import androidx.core.content.edit
import rikka.shizuku.Shizuku

object ShizukuPowerBackend {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    const val SETUP_URL = "https://shizuku.rikka.app/guide/setup/"
    private const val TAG = "ShizukuPowerBackend"
    private const val PREFERENCES = "shizuku-power"
    private const val KEY_KILL_SWITCH = "kill-switch-enabled"

    val elevatedActionIds: Set<String> = setOf(
        "airplane.toggle",
        "mobile.toggle",
        "screenshot.take",
        "reboot",
        "screen.off",
        "wake",
    )

    /** Defaults on so a process restart never enables privileged behavior before preferences load. */
    @Volatile
    var killSwitchEnabled: Boolean = true
        internal set

    fun initialize(context: Context) {
        ShizukuShellRunner.initialize(context)
        killSwitchEnabled = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH, true)
    }

    fun shutdown() {
        ShizukuShellRunner.shutdown()
    }

    fun setKillSwitchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_KILL_SWITCH, enabled) }
        killSwitchEnabled = enabled
        if (enabled) {
            ShizukuShellRunner.shutdown()
        } else {
            ShizukuShellRunner.initialize(context)
        }
    }

    fun inspect(context: Context): ShizukuPowerStatus = statusFor(
        managerInstalled = isPackageInstalled(context, MANAGER_PACKAGE),
        killSwitchEnabled = killSwitchEnabled,
        serviceRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
        permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false),
        privilegedTransportAvailable = ShizukuShellRunner.hasPrivilegedTransport(),
    )

    internal fun statusFor(
        managerInstalled: Boolean,
        killSwitchEnabled: Boolean = false,
        serviceRunning: Boolean = false,
        permissionGranted: Boolean = false,
        privilegedTransportAvailable: Boolean = false,
    ): ShizukuPowerStatus = when {
        !managerInstalled -> ShizukuPowerStatus(
            state = ShizukuPowerState.NotInstalled,
            summary = "Shizuku manager is not installed.",
        )
        killSwitchEnabled -> ShizukuPowerStatus(
            state = ShizukuPowerState.Disabled,
            summary = "Shizuku power mode is disabled by the persisted kill switch.",
        )
        !serviceRunning -> ShizukuPowerStatus(
            state = ShizukuPowerState.ManagerInstalled,
            summary = "Shizuku manager is installed but the service is not running.",
        )
        !permissionGranted -> ShizukuPowerStatus(
            state = ShizukuPowerState.PermissionNeeded,
            summary = "Shizuku is running but OpenTasker needs permission.",
        )
        !privilegedTransportAvailable -> ShizukuPowerStatus(
            state = ShizukuPowerState.BackendUnavailable,
            summary = "Shizuku permission is granted, but this build has no privileged user-service transport. " +
                "Elevated actions cannot run until the transport is available.",
        )
        else -> ShizukuPowerStatus(
            state = ShizukuPowerState.Ready,
            summary = "Shizuku is active, permission is granted, and a privileged transport is available.",
        )
    }

    fun hintForAction(actionId: String): ShizukuActionHint? =
        if (actionId in elevatedActionIds) {
            ShizukuActionHint(
                actionId = actionId,
                message = "This action requires Shizuku permission and its privileged user-service transport.",
            )
        } else {
            null
        }

    fun isReady(): Boolean =
        !killSwitchEnabled &&
            ShizukuShellRunner.hasPrivilegedTransport() &&
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission(requestCode: Int): Boolean =
        runCatching { Shizuku.requestPermission(requestCode) }
            .onFailure { AppLogger.error(TAG, "Failed to request Shizuku permission", it) }
            .isSuccess

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
    BackendUnavailable,
    Ready,
    Disabled,
}

data class ShizukuActionHint(
    val actionId: String,
    val message: String,
)
