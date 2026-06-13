package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
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
    override val id = "wifi.toggle"
    override val category = ActionCategory.SETTINGS

    @Suppress("DEPRECATION")
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
    override val id = "bluetooth.toggle"
    override val category = ActionCategory.SETTINGS

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Bluetooth permission is not granted")
        }
        val bm = ctx.app.getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter
            ?: return ActionResult.Failure("Bluetooth not available")

        if (Build.VERSION.SDK_INT >= 33) {
            return ActionResult.Failure("Android 13+ blocks direct Bluetooth enable/disable; use system Bluetooth settings instead")
        }

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
    override val id = "brightness.set"
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
    override val id = "volume.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val levelArg = args["level"] ?: return ActionResult.Failure("missing level")
        val audioManager = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        val streamType = streamType(args["stream"] ?: "music") ?: return ActionResult.Failure("invalid stream")

        return try {
            when (levelArg.lowercase()) {
                "mute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                else -> {
                    val max = audioManager.getStreamMaxVolume(streamType)
                    val level = levelArg.toIntOrNull()?.coerceIn(0, max)
                        ?: return ActionResult.Failure("invalid level: $levelArg")
                    audioManager.setStreamVolume(streamType, level, 0)
                }
            }
            ctx.logger("Volume ${args["stream"] ?: "music"}: $levelArg")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("volume change blocked by DND policy: ${ex.message}", ex)
        }
    }
}

/**
 * Toggle Airplane mode.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class AirplaneModeAction : Action {
    override val id = "airplane.toggle"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        ctx.logger("Airplane mode: $state")
        return ActionResult.Failure("Airplane mode changes are restricted to system or device-owner apps")
    }
}

/**
 * Toggle mobile data.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class MobileDataAction : Action {
    override val id = "mobile.toggle"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        ctx.logger("Mobile data: $state")
        return ActionResult.Failure("Mobile data changes are restricted to carrier, system, or device-owner apps")
    }
}

class DoNotDisturbAction : Action {
    override val id = "dnd.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val nm = ctx.app.getSystemService(NotificationManager::class.java)
            ?: return ActionResult.Failure("notification service not available")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult.Failure("Do Not Disturb access is not granted; enable it in Setup")
        }
        val mode = args["mode"] ?: "total_silence"
        val filter = when (mode.lowercase()) {
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority", "priority_only" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total_silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return ActionResult.Failure("invalid DND mode: $mode (use off/priority/alarms/total_silence)")
        }
        return try {
            nm.setInterruptionFilter(filter)
            ctx.logger("DND: $mode")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("DND change blocked: ${ex.message}", ex)
        }
    }
}

class RingerModeAction : Action {
    override val id = "ringer.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        val mode = args["mode"] ?: return ActionResult.Failure("missing mode argument")
        val ringerMode = when (mode.lowercase()) {
            "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return ActionResult.Failure("invalid ringer mode: $mode (use normal/vibrate/silent)")
        }
        return try {
            am.ringerMode = ringerMode
            ctx.logger("Ringer: $mode")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("ringer change blocked by DND policy: ${ex.message}", ex)
        }
    }
}

class TorchAction : Action {
    override val id = "torch.set"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        val cm = ctx.app.getSystemService(CameraManager::class.java)
            ?: return ActionResult.Failure("camera service not available")
        val cameraId = try {
            cm.cameraIdList.firstOrNull()
        } catch (_: CameraAccessException) { null }
            ?: return ActionResult.Failure("no camera with flash found")

        return try {
            when (state.lowercase()) {
                "on" -> cm.setTorchMode(cameraId, true)
                "off" -> cm.setTorchMode(cameraId, false)
                "toggle" -> {
                    cm.setTorchMode(cameraId, true)
                    ctx.logger("Torch: on (toggle always turns on; use explicit on/off for reliable state)")
                    return ActionResult.Success
                }
                else -> return ActionResult.Failure("invalid state: $state (use on/off/toggle)")
            }
            ctx.logger("Torch: $state")
            ActionResult.Success
        } catch (ex: CameraAccessException) {
            ActionResult.Failure("torch failed: ${ex.message}", ex)
        }
    }
}

private fun streamType(name: String): Int? = when (name.lowercase()) {
    "music", "media" -> AudioManager.STREAM_MUSIC
    "alarm" -> AudioManager.STREAM_ALARM
    "ring", "ringer" -> AudioManager.STREAM_RING
    "notification" -> AudioManager.STREAM_NOTIFICATION
    "system" -> AudioManager.STREAM_SYSTEM
    "voice", "call" -> AudioManager.STREAM_VOICE_CALL
    else -> null
}

/**
 * Set screen timeout (stay-on duration).
 *
 * Args:
 *   - "millis": milliseconds until screen times out (0 = never)
 */
class ScreenTimeoutAction : Action {
    override val id = "screen.timeout"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ms = args["millis"]?.toLongOrNull() ?: 30000L
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.toInt())
            ctx.logger("Screen timeout: ${ms / 1000}s")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed: ${e.message}")
        }
    }
}
