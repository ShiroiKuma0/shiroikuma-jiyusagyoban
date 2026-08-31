package com.opentasker.core.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
// The fork keeps plain Action implementations alongside upstream's DeclaredAction ones in this
// file, so both symbols stay imported.
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
 *   - "millis": one-shot duration in milliseconds (ignored when "pattern" is given)
 *   - "pattern": comma-separated waveform in ms, alternating OFF,ON and starting with an initial
 *     OFF delay (the Android waveform convention) — e.g. "0,150,100,150" = buzz 150 ms, pause
 *     100 ms, buzz 150 ms. For incoming-message-style multi-buzz vibrations (白い熊: 通知明滅).
 */
class VibrateAction : DeclaredAction(ActionCatalog.require("vibrate")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val patternArg = args["pattern"]?.trim().orEmpty()
        if (patternArg.isNotEmpty()) {
            val segments = patternArg.split(",").map { seg ->
                seg.trim().toLongOrNull()
                    ?: return ActionResult.Failure("invalid pattern segment: '${seg.trim()}' (comma-separated ms expected)")
            }
            if (segments.any { it < 0 || it > MAX_VIBRATE_MS }) {
                return ActionResult.Failure("pattern segments must be between 0 and $MAX_VIBRATE_MS ms")
            }
            if (segments.sum() !in MIN_VIBRATE_MS..MAX_VIBRATE_MS) {
                return ActionResult.Failure("pattern total must be between $MIN_VIBRATE_MS and $MAX_VIBRATE_MS ms")
            }
            return vibrate(ctx, VibrationEffect.createWaveform(segments.toLongArray(), -1), "Vibrate pattern $patternArg")
        }
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis (or pattern)")
        val millis = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (millis !in MIN_VIBRATE_MS..MAX_VIBRATE_MS) {
            return ActionResult.Failure("vibrate duration must be between $MIN_VIBRATE_MS and $MAX_VIBRATE_MS ms")
        }
        return vibrate(ctx, VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE), "Vibrate ${millis}ms")
    }

    private fun vibrate(ctx: ActionContext, effect: VibrationEffect, logLine: String): ActionResult {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)?.let {
                    (it as VibratorManager).defaultVibrator
                }
            } else {
                @Suppress("DEPRECATION")
                ctx.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return ActionResult.Failure("vibrator not available")

            vibrator.vibrate(effect)
            ctx.logger(logLine)
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
 */
class LockDeviceAction : DeclaredAction(ActionCatalog.require("lock")) {

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
 * Power off the device via Shizuku (shell `svc power shutdown`, falling back to `reboot -p`).
 * Requires Shizuku running + granted.
 */
class PowerOffAction : Action {
    override val id = "power.off"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (!ShizukuShell.available()) {
            return ActionResult.Failure("power off needs Shizuku running + access granted")
        }
        return withContext(Dispatchers.IO) {
            val ok = runCatching { ShizukuShell.exec("svc power shutdown").exitCode == 0 }.getOrDefault(false) ||
                runCatching { ShizukuShell.exec("reboot -p").exitCode == 0 }.getOrDefault(false)
            if (ok) {
                ctx.logger("Power off")
                ActionResult.Success
            } else {
                ActionResult.Failure("power off command failed")
            }
        }
    }
}

/**
 * Turn off screen.
 */
class ScreenOffAction : DeclaredAction(ActionCatalog.require("screen.off")) {

    // Accessibility GLOBAL_ACTION_LOCK_SCREEN first (no Shizuku needed — but it also LOCKS the device),
    // then fall back to the Shizuku KEYCODE_SLEEP keyevent (pure sleep, no lock). globalAction() does the
    // hybrid (in-process accessibility action, else the key event when keyCode != 0).
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Turn screen off", keyCode = 223)
}

/**
 * Turn on screen (wake device).
 *
 * Shizuku sends a directional wake keyevent. There is no keep-awake duration on that path,
 * so leftover `duration_sec` args are ignored rather than logged as if they were honoured.
 */
class WakeAction : DeclaredAction(ActionCatalog.require("wake")) {

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
class LogAction : DeclaredAction(ActionCatalog.require("log")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"] ?: ""
        ctx.logger(message)
        return ActionResult.Success
    }
}
