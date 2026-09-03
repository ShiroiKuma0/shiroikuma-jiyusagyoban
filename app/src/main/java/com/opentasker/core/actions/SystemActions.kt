package com.opentasker.core.actions

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.LockDeviceAdminReceiver

/**
 * Vibrate device.
 *
 * Args:
 *   - "millis": duration in milliseconds
 */
class VibrateAction : DeclaredAction(ActionCatalog.require("vibrate")) {

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
 * The Shizuku allowlist only admits a normal reboot. Metadata exposes no mode field, and
 * imported leftover `mode` args are ignored rather than failing a mapped Tasker reboot.
 */
class RebootAction : DeclaredAction(ActionCatalog.require("reboot")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Reboot")
        return ctx.runShizukuAction("reboot", "Reboot")
    }
}

/**
 * Lock device (secure lock).
 *
 * Uses `DevicePolicyManager.lockNow`, which needs an active device admin and nothing else. That is
 * the only route a normal app has: it needs no root, no Shizuku, and no accessibility service, so
 * it keeps working under Android 17 Advanced Protection.
 */
class LockDeviceAction : DeclaredAction(ActionCatalog.require("lock")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Lock device")
        if (!LockDeviceAdminReceiver.isActive(ctx.app)) {
            // Named rather than generic: the user can act on this one, and the row is one tap away.
            return ActionResult.Failure(
                "device lock needs the Lock screen admin turned on in Setup"
            )
        }
        val manager = ctx.app.getSystemService(DevicePolicyManager::class.java)
            ?: return ActionResult.Failure("device policy service is unavailable")
        return runCatching {
            manager.lockNow()
            ActionResult.Success
        }.getOrElse { error ->
            // The admin can be deactivated between the check above and this call, and a
            // SecurityException here is exactly that race rather than a bug.
            ActionResult.Failure("device lock failed: ${error.message}")
        }
    }
}

/**
 * Turn off screen.
 */
class ScreenOffAction : DeclaredAction(ActionCatalog.require("screen.off")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Screen off")
        return ctx.runShizukuAction("screen.off", "Screen off")
    }
}

/**
 * Turn on screen (wake device).
 *
 * Shizuku sends a directional wake keyevent. There is no keep-awake duration on that path,
 * so leftover `duration_sec` args are ignored rather than logged as if they were honoured.
 */
class WakeAction : DeclaredAction(ActionCatalog.require("wake")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Wake")
        return ctx.runShizukuAction("wake", "Wake")
    }
}

/**
 * Log a message to the run log (visible in history).
 *
 * Args:
 *   - "message": text to log
 */
class LogAction : DeclaredAction(ActionCatalog.require("log")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"] ?: ""
        ctx.logger(message)
        return ActionResult.Success
    }
}
