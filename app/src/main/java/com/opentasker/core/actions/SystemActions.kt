package com.opentasker.core.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vibrate device.
 *
 * Args:
 *   - "millis": duration in milliseconds
 */
class VibrateAction : Action {
    override val id = "vibrate"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis")
        val millis = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (millis !in MIN_VIBRATE_MS..MAX_VIBRATE_MS) {
            return ActionResult.Failure("vibrate duration must be between $MIN_VIBRATE_MS and $MAX_VIBRATE_MS ms")
        }
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)?.let {
                    (it as VibratorManager).defaultVibrator
                }
            } else {
                @Suppress("DEPRECATION")
                ctx.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return ActionResult.Failure("vibrator not available")

            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            ctx.logger("Vibrate ${millis}ms")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("vibrate failed: ${e.message}")
        }
    }

    companion object {
        private const val MIN_VIBRATE_MS = 1L
        private const val MAX_VIBRATE_MS = 10_000L
    }
}

/**
 * Reboot device.
 *
 * Args:
 *   - "mode": "recovery", "bootloader", or blank for normal reboot
 */
class RebootAction : Action {
    override val id = "reboot"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"]?.ifBlank { null }
        ctx.logger("Reboot${mode?.let { " ($it)" } ?: ""}")
        return ActionResult.Failure("Reboot requires privileged device-owner or system app access")
    }
}

/**
 * Lock device (secure lock).
 */
class LockDeviceAction : Action {
    override val id = "lock"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Lock device")
        return ActionResult.Failure("Device lock requires a configured DevicePolicyManager admin")
    }
}

/**
 * Lockdown: lock now and require the PIN/password on the next unlock (biometrics disabled), i.e. the
 * power-menu "Lockdown". Uses our Device Admin: tries `lockNow(FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)`
 * (forces credential where the OS allows it for the admin) and falls back to a plain `lockNow()`.
 * Requires the user to have enabled 白い熊 自由作業盤 as a Device Admin.
 */
class LockdownAction : Action {
    override val id = "screen.lockdown"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val dpm = ctx.app.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?: return ActionResult.Failure("DevicePolicyManager unavailable")
        val admin = android.content.ComponentName(ctx.app, com.opentasker.core.admin.DeviceAdmin::class.java)
        if (!dpm.isAdminActive(admin)) {
            return ActionResult.Failure("Enable Device Admin first (Permissions → Device admin / lockdown)")
        }
        return try {
            try {
                dpm.lockNow(android.app.admin.DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
            } catch (e: SecurityException) {
                dpm.lockNow() // non-managed admin can't evict the CE key → plain immediate lock
            }
            ctx.logger("Lockdown")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("lockdown failed: ${e.message}")
        }
    }
}

/**
 * Turn off screen.
 */
class ScreenOffAction : Action {
    override val id = "screen.off"
    override val category = ActionCategory.SETTINGS

    // Accessibility GLOBAL_ACTION_LOCK_SCREEN first (no Shizuku needed — but it also LOCKS the device),
    // then fall back to the Shizuku KEYCODE_SLEEP keyevent (pure sleep, no lock). globalAction() does the
    // hybrid (in-process accessibility action, else the key event when keyCode != 0).
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Turn screen off", keyCode = 223)
}

/**
 * Turn on screen (wake device).
 *
 * Args:
 *   - "duration_sec": how long to keep screen on
 */
class WakeAction : Action {
    override val id = "wake"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (!ShizukuShell.available()) return ActionResult.Failure("Screen wake needs Shizuku")
        val ok = runCatching {
            withContext(Dispatchers.IO) { ShizukuShell.exec("input keyevent 224").exitCode == 0 } // KEYCODE_WAKEUP
        }.getOrDefault(false)
        ctx.logger(if (ok) "Wake (Shizuku)" else "Wake failed")
        return if (ok) ActionResult.Success else ActionResult.Failure("Wake keyevent failed")
    }
}

/**
 * Log a message to the run log (visible in history).
 *
 * Args:
 *   - "message": text to log
 */
class LogAction : Action {
    override val id = "log"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"] ?: ""
        ctx.logger(message)
        return ActionResult.Success
    }
}
