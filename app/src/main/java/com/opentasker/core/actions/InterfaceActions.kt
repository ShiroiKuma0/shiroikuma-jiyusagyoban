package com.opentasker.core.actions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

// ---------------------------------------------------------------------------------------------
// Wave 3 — gated actions that DO work on stock Android: accessibility global actions (Back,
// Recents, panels, power, lock), Place Call (CALL_PHONE), and Auto Brightness (WRITE_SETTINGS).
// (Shizuku-backed actions — Run Shell, Secure/Global settings, Location Mode — are deferred until
// the Shizuku integration is built; the backend is currently a stub.)
// ---------------------------------------------------------------------------------------------

private fun globalAction(ctx: ActionContext, action: Int, label: String): ActionResult {
    if (!ShiroiKumaAccessibilityService.isConnected) {
        return ActionResult.Failure("Enable the 白い熊 自由作業盤 accessibility service in Android settings first")
    }
    return if (ShiroiKumaAccessibilityService.perform(action)) {
        ctx.logger(label)
        ActionResult.Success
    } else {
        ActionResult.Failure("$label not available on this device")
    }
}

/** `Back Button` (Tasker 245). */
class NavBackAction : Action {
    override val id = "nav.back"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>) =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_BACK, "Back")
}

/** `Show Recents` (Tasker 247). */
class NavRecentsAction : Action {
    override val id = "nav.recents"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>) =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
}

/** `Status Bar` / Notifications panel (Tasker 512). */
class NotificationsPanelAction : Action {
    override val id = "panel.notifications"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>) =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications panel")
}

/** `Quick Settings` (Tasker 219). */
class QuickSettingsPanelAction : Action {
    override val id = "panel.quicksettings"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>) =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings")
}

/** Power dialog (long-press power menu). */
class PowerDialogAction : Action {
    override val id = "nav.power"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>) =
        globalAction(ctx, AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "Power dialog")
}

/** Lock the screen via accessibility (Android 9+); no device-admin needed. */
class LockScreenAction : Action {
    override val id = "screen.lock"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ActionResult.Failure("Lock via accessibility requires Android 9+")
        }
        return globalAction(ctx, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Lock screen")
    }
}

/** `Call` (Tasker 90) — place a phone call (or open the dialer if CALL_PHONE isn't granted). */
class PlaceCallAction : Action {
    override val id = "call.place"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val number = args["number"]?.trim().orEmpty()
        if (number.isEmpty()) return ActionResult.Failure("missing number")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val granted = ctx.app.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.app.startActivity(intent)
            ctx.logger(if (granted) "Calling $number" else "Dialer for $number (CALL_PHONE not granted)")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("could not place call: ${e.message}")
        }
    }
}

/** `Auto Brightness` (Tasker 808) — toggle automatic screen brightness (WRITE_SETTINGS). */
class AutoBrightnessAction : Action {
    override val id = "brightness.auto"
    override val category = ActionCategory.SETTINGS
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        val cr = ctx.app.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val auto = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        val target = when (args["state"]?.trim()?.lowercase()) {
            "on", "auto", "automatic", "true", "1" -> auto
            "off", "manual", "false", "0" -> manual
            "toggle", null, "" -> if (current == auto) manual else auto
            else -> return ActionResult.Failure("state must be on / off / toggle")
        }
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, target)
            ctx.logger("Auto brightness ${if (target == auto) "on" else "off"}")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed to set auto brightness: ${e.message}")
        }
    }
}
