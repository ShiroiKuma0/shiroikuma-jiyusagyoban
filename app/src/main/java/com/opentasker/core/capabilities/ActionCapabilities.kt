package com.opentasker.core.capabilities

import androidx.annotation.StringRes
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.scripting.TermuxScriptBackend

enum class CapabilityLevel {
    Supported,
    RequiresSetup,
    Unsupported,
}

data class ActionCapability(
    val level: CapabilityLevel,
    val reason: String,
    @get:StringRes val reasonRes: Int,
) {
    val canAdd: Boolean
        get() = level != CapabilityLevel.Unsupported
}

object ActionCapabilityRegistry {
    private val supported = ActionCapability(CapabilityLevel.Supported, "Ready", R.string.capability_ready)
    private val unknown = ActionCapability(
        CapabilityLevel.Unsupported,
        "Unknown actions are not classified and cannot be added, imported, or enabled.",
        R.string.capability_unknown_action,
    )

    private val capabilities = mapOf(
        "notify.show" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+.", R.string.capability_notification_permission),
        "notify.cancel" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels a posted notification by tag and/or ID. Requires notification permission on Android 13+.", R.string.capability_notification_cancel_permission),
        "plugin.locale.fire" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible plugin; requests are dispatched only to an explicit package.", R.string.capability_locale_fire_setup),
        "plugin.locale.query" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible condition plugin; queries are explicit ordered broadcasts with timeout handling.", R.string.capability_locale_query_setup),
        "wifi.toggle" to ActionCapability(CapabilityLevel.Unsupported, "Android 10+ blocks direct WiFi toggles for normal apps.", R.string.capability_wifi_unsupported),
        "bluetooth.toggle" to bluetoothCapability(),
        "brightness.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", R.string.capability_write_settings),
        "volume.set" to volumeCapability("May be blocked by Do Not Disturb policy access."),
        "dnd.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Do Not Disturb access.", R.string.capability_dnd_access),
        "ringer.set" to volumeCapability("May require Do Not Disturb access on some devices when switching to silent mode."),
        "torch.set" to ActionCapability(CapabilityLevel.Supported, "Uses camera flashlight.", R.string.capability_torch_ready),
        "airplane.toggle" to elevatedUnsupported("airplane.toggle", "Airplane mode changes require system or device-owner privileges.", R.string.capability_airplane_unsupported),
        "mobile.toggle" to elevatedUnsupported("mobile.toggle", "Mobile data changes require carrier, system, or device-owner privileges.", R.string.capability_mobile_data_unsupported),
        "sms.send" to smsCapability(),
        "screenshot.take" to elevatedUnsupported("screenshot.take", "Screenshots require MediaProjection consent or privileged shell access.", R.string.capability_screenshot_unsupported),
        "sound.play" to audioOutputCapability("Plays audio from a file path or content URI."),
        "sound.stop" to mediaKeyCapability("Stop playback via media key dispatch."),
        "sound.pause" to mediaKeyCapability("Pause playback via media key dispatch."),
        "track.next" to mediaKeyCapability("Next track via media key dispatch."),
        "track.previous" to mediaKeyCapability("Previous track via media key dispatch."),
        "media.mute" to volumeCapability("Mutes a stream. May be blocked by Do Not Disturb policy."),
        "tts.speak" to audioOutputCapability("Uses Android TTS engine to speak text aloud."),
        "reboot" to elevatedUnsupported("reboot", "Reboot requires privileged device-owner or system app access.", R.string.capability_reboot_unsupported),
        "lock" to ActionCapability(CapabilityLevel.Unsupported, "Device lock requires configured device-admin support.", R.string.capability_lock_unsupported),
        "tile.set" to ActionCapability(CapabilityLevel.Unsupported, "Quick Settings tile updates are not functional yet; per-task tiles are a planned feature.", R.string.capability_tile_unsupported),
        "screen.off" to elevatedUnsupported("screen.off", "Screen-off requires privileged power management access.", R.string.capability_screen_off_unsupported),
        "wake" to elevatedUnsupported("wake", "Wake requires a foreground activity or privileged wake flow.", R.string.capability_wake_unsupported),
        TermuxScriptBackend.ACTION_ID to ActionCapability(
            CapabilityLevel.RequiresSetup,
            TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID)?.message
                ?: "Termux 0.109+, RUN_COMMAND permission, and an approved script hash are required.",
            R.string.capability_termux_setup,
        ),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported OpenTasker action.", R.string.capability_tasker_import_unsupported),
    )

    fun get(actionId: String): ActionCapability = capabilities[actionId]
        ?: if (AutomationSensitivityRegistry.isKnown(actionId)) supported else unknown

    private fun bluetoothCapability(): ActionCapability =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ActionCapability(CapabilityLevel.Unsupported, "Android 13+ blocks direct Bluetooth enable/disable for normal apps.", R.string.capability_bluetooth_unsupported)
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires Bluetooth permission.", R.string.capability_bluetooth_permission)
        }

    private fun smsCapability(): ActionCapability =
        if (BuildConfig.SMS_ACTION_AVAILABLE) {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires SMS permission; Play builds omit SMS actions for policy compliance.", R.string.capability_sms_permission)
        } else {
            ActionCapability(CapabilityLevel.Unsupported, "SMS action is unavailable in this distribution because SMS and phone-state permissions are omitted for Play policy compliance.", R.string.capability_sms_distribution_unsupported)
        }

    internal fun audioOutputCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.outputCapabilityReason(reason), R.string.capability_audio_output_restricted)
        } else {
            ActionCapability(CapabilityLevel.Supported, reason, R.string.capability_audio_output_ready)
        }

    internal fun mediaKeyCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.mediaKeyCapabilityReason(reason), R.string.capability_media_key_restricted)
        } else {
            ActionCapability(CapabilityLevel.Supported, reason, R.string.capability_media_key_ready)
        }

    internal fun volumeCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.volumeCapabilityReason(reason), R.string.capability_volume_restricted)
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, reason, R.string.capability_volume_policy)
        }

    private fun audioOutputCapability(reason: String): ActionCapability =
        audioOutputCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun mediaKeyCapability(reason: String): ActionCapability =
        mediaKeyCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun volumeCapability(reason: String): ActionCapability =
        volumeCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun elevatedUnsupported(actionId: String, reason: String, @StringRes reasonRes: Int): ActionCapability =
        ActionCapability(
            CapabilityLevel.Unsupported,
            "$reason ${ShizukuPowerBackend.hintForAction(actionId)?.message ?: "No privileged backend is available."}",
            reasonRes,
        )
}
