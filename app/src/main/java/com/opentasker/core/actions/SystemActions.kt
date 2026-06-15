package com.opentasker.core.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

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
        val millis = (args["millis"]?.toLongOrNull() ?: 100L).coerceIn(1L, 10_000L)
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
 * Turn off screen.
 */
class ScreenOffAction : Action {
    override val id = "screen.off"
    override val category = ActionCategory.SETTINGS

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
class WakeAction : Action {
    override val id = "wake"
    override val category = ActionCategory.SETTINGS

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
class LogAction : Action {
    override val id = "log"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"] ?: ""
        ctx.logger(message)
        return ActionResult.Success
    }
}
