package com.opentasker.core.capabilities

import com.opentasker.app.BuildConfig
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.scripting.TermuxScriptBackend

enum class CapabilityLevel {
    Supported,
    RequiresSetup,
    Unsupported,
}

/** The OS permission / service an action needs, so the editor can check it live and deep-link the fix. */
enum class CapabilityRequirement {
    None,
    Accessibility,
    Shizuku,
    WriteSettings,
    PostNotifications,
    NotificationListener,
    Overlay,
    Dnd,
    AllFiles,
}

data class ActionCapability(
    val level: CapabilityLevel,
    val reason: String,
    val requirement: CapabilityRequirement = CapabilityRequirement.None,
) {
    val canAdd: Boolean
        get() = level != CapabilityLevel.Unsupported
}

object ActionCapabilityRegistry {
    private val supported = ActionCapability(CapabilityLevel.Supported, "Ready")

    private val capabilities = mapOf(
        "notify.show" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+.", CapabilityRequirement.PostNotifications),
        "notify.cancel" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels a posted notification by tag and/or ID. Requires notification permission on Android 13+.", CapabilityRequirement.PostNotifications),
        "notify.dismiss" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels another app's notifications by package. Requires notification-access (listener) permission.", CapabilityRequirement.NotificationListener),
        "plugin.locale.fire" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible plugin; requests are dispatched only to an explicit package."),
        "plugin.locale.query" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible condition plugin; queries are explicit ordered broadcasts with timeout handling."),
        "wifi.toggle" to shizukuCapability("WiFi toggle on Android 10+"),
        "bluetooth.toggle" to bluetoothCapability(),
        "brightness.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", CapabilityRequirement.WriteSettings),
        "volume.set" to volumeCapability("May be blocked by Do Not Disturb policy access."),
        "dnd.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Do Not Disturb access.", CapabilityRequirement.Dnd),
        "ringer.set" to ActionCapability(CapabilityLevel.RequiresSetup, "May require Do Not Disturb access on some devices when switching to silent mode."),
        "torch.set" to ActionCapability(CapabilityLevel.Supported, "Uses camera flashlight."),
        "airplane.toggle" to shizukuCapability("Airplane mode"),
        "mobile.toggle" to shizukuCapability("Mobile data"),
        "sms.send" to smsCapability(),
        "screenshot.take" to shizukuCapability("Screenshot"),
        "location.mode" to shizukuCapability("Location mode"),
        "ime.set" to shizukuCapability("Set keyboard"),
        "sound.play" to audioOutputCapability("Plays audio from a file path or content URI; a file outside the app's own folders needs All files access.")
            .let { if (it.level == CapabilityLevel.Supported) it.copy(requirement = CapabilityRequirement.AllFiles) else it },
        "sound.stop" to mediaKeyCapability("Stop playback via media key dispatch."),
        "sound.pause" to mediaKeyCapability("Pause playback via media key dispatch."),
        "track.next" to mediaKeyCapability("Next track via media key dispatch."),
        "track.previous" to mediaKeyCapability("Previous track via media key dispatch."),
        "media.mute" to volumeCapability("Mutes a stream. May be blocked by Do Not Disturb policy."),
        "tts.speak" to audioOutputCapability("Uses Android TTS engine to speak text aloud."),
        "reboot" to elevatedUnsupported("reboot", "Reboot requires privileged device-owner or system app access."),
        "lock" to ActionCapability(CapabilityLevel.Unsupported, "Device lock requires configured device-admin support."),
        "screen.off" to accessibilityCapability(),
        "wake" to elevatedUnsupported("wake", "Wake requires a foreground activity or privileged wake flow."),
        "app.freeze" to shizukuCapability("Freeze app (pm disable-user)"),
        "app.unfreeze" to shizukuCapability("Unfreeze app (pm enable)"),
        "tasks.launchers" to shizukuCapability("Create launcher tasks"),
        TermuxScriptBackend.ACTION_ID to ActionCapability(
            CapabilityLevel.RequiresSetup,
            TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID)?.message
                ?: "Termux script dispatch ready when Termux and Termux:Tasker are installed.",
        ),
        "flash" to ActionCapability(CapabilityLevel.RequiresSetup, "Custom colours, border and position need \"display over other apps\"; without it the flash falls back to a plain toast.", CapabilityRequirement.Overlay),
        "state.get" to ActionCapability(CapabilityLevel.Supported, "Reads battery / charging / WiFi / airplane into variables; no permissions needed."),
        "clipboard.get" to ActionCapability(CapabilityLevel.RequiresSetup, "Android 10+ blocks clipboard reads unless the app is focused; may return empty from the background."),
        "apps.list" to ActionCapability(CapabilityLevel.RequiresSetup, "Android 11+ package visibility limits the result to apps this app can see."),
        "nav.back" to accessibilityCapability(),
        "nav.recents" to accessibilityCapability(),
        "panel.notifications" to accessibilityCapability(),
        "panel.quicksettings" to accessibilityCapability(),
        "nav.power" to accessibilityCapability(),
        "screen.lock" to accessibilityCapability(),
        "screen.lockdown" to ActionCapability(CapabilityLevel.RequiresSetup, "Enable 白い熊 自由作業盤 as a Device Admin (Permissions screen) so it can lock and require the PIN/password."),
        "call.place" to ActionCapability(CapabilityLevel.RequiresSetup, "Needs the Phone (CALL_PHONE) permission to dial directly; otherwise opens the dialer."),
        "brightness.auto" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", CapabilityRequirement.WriteSettings),
        "setting.put" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access; only the System namespace is writable without Shizuku.", CapabilityRequirement.WriteSettings),
        "dialog.input" to dialogCapability(),
        "dialog.list" to dialogCapability(),
        "dialog.text" to dialogCapability(),
        "shell.run" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Shizuku installed, started, and access granted to this app.", CapabilityRequirement.Shizuku),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported 白い熊 自由作業盤 action."),
    )

    fun get(actionId: String): ActionCapability = capabilities[actionId] ?: supported

    private fun bluetoothCapability(): ActionCapability =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ActionCapability(CapabilityLevel.Unsupported, "Android 13+ blocks direct Bluetooth enable/disable for normal apps.")
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires Bluetooth permission.")
        }

    private fun smsCapability(): ActionCapability =
        if (BuildConfig.SMS_ACTION_AVAILABLE) {
            ActionCapability(CapabilityLevel.RequiresSetup, "Requires SMS permission; Play builds omit SMS actions for policy compliance.")
        } else {
            ActionCapability(CapabilityLevel.Unsupported, "SMS action is unavailable in this distribution because SMS and phone-state permissions are omitted for Play policy compliance.")
        }

    internal fun audioOutputCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.Unsupported, AndroidAudioHardening.outputCapabilityReason(reason))
        } else {
            ActionCapability(CapabilityLevel.Supported, reason)
        }

    private fun shizukuCapability(feature: String): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "$feature runs through Shizuku — install and start Shizuku, then grant this app access.", CapabilityRequirement.Shizuku)

    private fun dialogCapability(): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "Shows over other apps; from a background trigger it needs the \"display over other apps\" permission. Always works when run from the app.", CapabilityRequirement.Overlay)

    private fun accessibilityCapability(): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "Enable the 白い熊 自由作業盤 accessibility service in Android settings.", CapabilityRequirement.Accessibility)

    internal fun mediaKeyCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.Unsupported, AndroidAudioHardening.mediaKeyCapabilityReason(reason))
        } else {
            ActionCapability(CapabilityLevel.Supported, reason)
        }

    internal fun volumeCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.Unsupported, AndroidAudioHardening.volumeCapabilityReason(reason))
        } else {
            ActionCapability(CapabilityLevel.RequiresSetup, reason)
        }

    private fun audioOutputCapability(reason: String): ActionCapability =
        audioOutputCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun mediaKeyCapability(reason: String): ActionCapability =
        mediaKeyCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun volumeCapability(reason: String): ActionCapability =
        volumeCapabilityForSdk(android.os.Build.VERSION.SDK_INT, reason)

    private fun elevatedUnsupported(actionId: String, reason: String): ActionCapability =
        if (ShizukuPowerBackend.isReady()) {
            ActionCapability(CapabilityLevel.RequiresSetup, "$reason Shizuku elevated mode is active.")
        } else {
            ActionCapability(
                CapabilityLevel.Unsupported,
                "$reason ${ShizukuPowerBackend.hintForAction(actionId)?.message ?: "Optional elevated backend is not active."}",
            )
        }
}
