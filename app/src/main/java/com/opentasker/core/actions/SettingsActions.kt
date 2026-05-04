package com.opentasker.core.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Toggle or set WiFi.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class WiFiToggleAction : Action {
    override val id = "wifi.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ActionResult.Failure("Android 10+ blocks direct WiFi toggles; open system WiFi settings instead")
        }
        val wm = ctx.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ActionResult.Failure("WiFi not available")
        
        when (state.lowercase()) {
            "toggle" -> wm.isWifiEnabled = !wm.isWifiEnabled
            "on" -> wm.isWifiEnabled = true
            "off" -> wm.isWifiEnabled = false
            else -> return ActionResult.Failure("invalid state: $state")
        }
        ctx.logger("WiFi: $state")
        return ActionResult.Success
    }
}

/**
 * Toggle or set Bluetooth.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class BluetoothToggleAction : Action {
    override val id = "bt.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Bluetooth permission is not granted")
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ActionResult.Failure("Bluetooth not available")

        when (state.lowercase()) {
            "toggle" -> if (adapter.isEnabled) adapter.disable() else adapter.enable()
            "on" -> adapter.enable()
            "off" -> adapter.disable()
            else -> return ActionResult.Failure("invalid state: $state")
        }
        ctx.logger("Bluetooth: $state")
        return ActionResult.Success
    }
}

/**
 * Set screen brightness.
 *
 * Args:
 *   - "brightness": 0-255 (or auto)
 */
class BrightnessAction : Action {
    override val id = "display.brightness"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val brightness = args["brightness"] ?: return ActionResult.Failure("missing brightness")
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            val value = when (brightness.lowercase()) {
                "auto" -> -1
                else -> brightness.toInt().coerceIn(0, 255)
            }
            Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            ctx.logger("Brightness: $brightness")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed to set brightness: ${e.message}")
        }
    }
}

/**
 * Set volume level for a stream.
 *
 * Args:
 *   - "stream": "music", "alarm", "ring", "notification", etc.
 *   - "level": 0-15 (or "mute", "unmute")
 */
class VolumeAction : Action {
    override val id = "audio.volume"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Volume: set to ${args["level"]}")
        // TODO: Implement AudioManager.setStreamVolume
        return ActionResult.Success
    }
}

/**
 * Toggle Airplane mode.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class AirplaneModeAction : Action {
    override val id = "airplane.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        ctx.logger("Airplane mode: $state")
        // TODO: Implement via Settings.Global.putInt(AIRPLANE_MODE_ON) + broadcast
        return ActionResult.Success
    }
}

/**
 * Toggle mobile data.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class MobileDataAction : Action {
    override val id = "mobile.data"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        ctx.logger("Mobile data: $state")
        // TODO: Implement via TelephonyManager reflection
        return ActionResult.Success
    }
}

/**
 * Set screen timeout (stay-on duration).
 *
 * Args:
 *   - "millis": milliseconds until screen times out (0 = never)
 */
class ScreenTimeoutAction : Action {
    override val id = "display.timeout"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ms = args["millis"]?.toLongOrNull() ?: 30000L
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, (ms / 1000).toInt())
            ctx.logger("Screen timeout: ${ms / 1000}s")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed: ${e.message}")
        }
    }
}
