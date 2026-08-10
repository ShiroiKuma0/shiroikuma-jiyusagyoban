package com.opentasker.core.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

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
 * Args:
 *   - "mode": "recovery", "bootloader", or blank for normal reboot
 */
class RebootAction : DeclaredAction(ActionCatalog.require("reboot")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"]?.ifBlank { null }
        ctx.logger("Reboot${mode?.let { " ($it)" } ?: ""}")
        return ActionResult.Failure("Reboot requires privileged device-owner or system app access")
    }
}

/**
 * Lock device (secure lock).
 */
class LockDeviceAction : DeclaredAction(ActionCatalog.require("lock")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Lock device")
        return ActionResult.Failure("Device lock requires a configured DevicePolicyManager admin")
    }
}

/**
 * Turn off screen.
 */
class ScreenOffAction : DeclaredAction(ActionCatalog.require("screen.off")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Screen off")
        return ActionResult.Failure("Screen-off requires privileged power management access")
    }
}

/**
 * Turn on screen (wake device).
 *
 * Args:
 *   - "duration_sec": how long to keep screen on
 */
class WakeAction : DeclaredAction(ActionCatalog.require("wake")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val dur = args["duration_sec"]?.toLongOrNull() ?: 10L
        ctx.logger("Wake (${dur}s)")
        return ActionResult.Failure("Screen wake requires a foreground activity or privileged wake flow")
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
