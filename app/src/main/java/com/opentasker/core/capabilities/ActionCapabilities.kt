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
    DeviceAdmin,
    Microphone,
}

data class ActionCapability(
    val level: CapabilityLevel,
    val reason: String,
    val requirement: CapabilityRequirement = CapabilityRequirement.None,
    /**
     * When true, a task is HARD-BLOCKED (with a dialog, won't run) if [requirement] isn't granted at run
     * time. Default false: most actions degrade gracefully (e.g. flash → toast) and must not block. Only
     * actions that genuinely do nothing — and silently break things — without their special access opt in.
     */
    val blocking: Boolean = false,
) {
    val canAdd: Boolean
        get() = level != CapabilityLevel.Unsupported
}

object ActionCapabilityRegistry {
    private val supported = ActionCapability(CapabilityLevel.Supported, "Ready")

    private val capabilities = mapOf(
        "notify.show" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+.", CapabilityRequirement.PostNotifications),
        "notify.cancel" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels a posted notification by tag and/or ID. Requires notification permission on Android 13+.", CapabilityRequirement.PostNotifications),
        "notify.dismiss" to ActionCapability(CapabilityLevel.RequiresSetup, "Cancels another app's notifications by package. Requires notification-access (listener) permission.", CapabilityRequirement.NotificationListener, blocking = true),
        "plugin.locale.fire" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible plugin; requests are dispatched only to an explicit package."),
        "plugin.locale.query" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible condition plugin; queries are explicit ordered broadcasts with timeout handling."),
        "wifi.toggle" to shizukuCapability("WiFi toggle on Android 10+"),
        "bluetooth.toggle" to bluetoothCapability(),
        "brightness.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", CapabilityRequirement.WriteSettings, blocking = true),
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
        "media.playpause" to mediaKeyCapability("Toggle play/pause via media key dispatch."),
        "track.next" to mediaKeyCapability("Next track via media key dispatch."),
        "track.previous" to mediaKeyCapability("Previous track via media key dispatch."),
        "media.mute" to volumeCapability("Mutes a stream. May be blocked by Do Not Disturb policy."),
        "tts.speak" to audioOutputCapability("Uses Android TTS engine to speak text aloud."),
        "reboot" to elevatedUnsupported("reboot", "Reboot requires privileged device-owner or system app access."),
        "power.off" to ActionCapability(CapabilityLevel.RequiresSetup, "Powers off the device through Shizuku — install and start Shizuku, then grant this app access.", CapabilityRequirement.Shizuku, blocking = true),
        "lock" to ActionCapability(CapabilityLevel.Unsupported, "Device lock requires configured device-admin support."),
        "tile.set" to ActionCapability(CapabilityLevel.Unsupported, "Quick Settings tile updates are not functional yet; per-task tiles are a planned feature."),
        "screen.off" to accessibilityCapability(),
        // Fork: WakeAction runs `input keyevent 224` through ShizukuShell — a plain Shizuku-gated
        // action like shell.run, NOT upstream's (never-shipped) privileged transport. Upstream's
        // elevatedUnsupported() here made the pre-flight hard-fail every task containing a wake.
        "wake" to shizukuCapability("Screen wake (KEYCODE_WAKEUP)"),
        "app.freeze" to shizukuCapability("Freeze app (pm disable-user)"),
        "share.relays" to shizukuCapability("Generate + install per-app share relays"),
        "app.unfreeze" to shizukuCapability("Unfreeze app (pm enable)"),
        "tasks.launchers" to shizukuCapability("Create launcher tasks"),
        TermuxScriptBackend.ACTION_ID to ActionCapability(
            CapabilityLevel.RequiresSetup,
            TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID)?.message
                ?: "Termux script dispatch ready when Termux and Termux:Tasker are installed.",
        ),
        "flash" to ActionCapability(CapabilityLevel.RequiresSetup, "Custom colours, border and position need \"display over other apps\"; without it the flash falls back to a plain toast.", CapabilityRequirement.Overlay),
        "bubble.flash_add" to ActionCapability(CapabilityLevel.RequiresSetup, "The flash bubbles render only with \"display over other apps\" and only while the Desktop launcher is foreground.", CapabilityRequirement.Overlay),
        "bubble.flashkill_show" to ActionCapability(CapabilityLevel.RequiresSetup, "The kill-all icon renders only with \"display over other apps\" and only while the Desktop launcher is foreground.", CapabilityRequirement.Overlay),
        "state.get" to ActionCapability(CapabilityLevel.Supported, "Reads battery / charging / WiFi / airplane into variables; no permissions needed."),
        "clipboard.get" to ActionCapability(CapabilityLevel.RequiresSetup, "Android 10+ blocks clipboard reads unless the app is focused; may return empty from the background."),
        "apps.list" to ActionCapability(CapabilityLevel.RequiresSetup, "Android 11+ package visibility limits the result to apps this app can see."),
        "nav.back" to accessibilityCapability(),
        "nav.recents" to accessibilityCapability(),
        "panel.notifications" to accessibilityCapability(),
        "panel.quicksettings" to accessibilityCapability(),
        "nav.power" to accessibilityCapability(),
        "screen.lock" to accessibilityCapability(),
        "screen.lockdown" to ActionCapability(CapabilityLevel.RequiresSetup, "Enable 白い熊 自由作業盤 as a Device Admin (Permissions screen) so it can lock and require the PIN/password.", CapabilityRequirement.DeviceAdmin, blocking = true),
        "scene.show" to accessibilityCapability(),
        "call.place" to ActionCapability(CapabilityLevel.RequiresSetup, "Needs the Phone (CALL_PHONE) permission to dial directly; otherwise opens the dialer."),
        "brightness.auto" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access.", CapabilityRequirement.WriteSettings, blocking = true),
        "setting.put" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access; only the System namespace is writable without Shizuku.", CapabilityRequirement.WriteSettings, blocking = true),
        "system.set_locale" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access plus the adb-granted CHANGE_CONFIGURATION permission (pm grant … android.permission.CHANGE_CONFIGURATION).", CapabilityRequirement.WriteSettings, blocking = true),
        "dialog.input" to dialogCapability(),
        "dialog.list" to dialogCapability(),
        "dialog.text" to dialogCapability(),
        "shell.run" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Shizuku installed, started, and access granted to this app.", CapabilityRequirement.Shizuku, blocking = true),
        // Switching which SIM carries data goes through ISub.setDefaultDataSubId, which needs
        // MODIFY_PHONE_STATE — held by shell, so Shizuku is the whole requirement.
        "sim.data.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Switches the SIM that carries mobile data — requires Shizuku installed, started, and access granted to this app.", CapabilityRequirement.Shizuku, blocking = true),
        "sim.list" to ActionCapability(CapabilityLevel.Supported, "Reads the active SIM slots and which one carries data."),
        // Pins its own network and transfers over it; no special access beyond the declared
        // CHANGE_NETWORK_STATE, and it never changes the system's default route.
        "net.speedtest.cancel" to ActionCapability(CapabilityLevel.Supported, "Aborts a running speed test immediately."),
        "net.speedtest" to ActionCapability(CapabilityLevel.Supported, "Measures download/upload throughput over a chosen transport (mobile or WiFi), time-boxed and byte-capped."),
        "audio.record.start" to ActionCapability(CapabilityLevel.RequiresSetup, "Records the microphone — needs the Microphone (RECORD_AUDIO) permission.", CapabilityRequirement.Microphone, blocking = true),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported 白い熊 自由作業盤 action."),
    )

    /**
     * An action's capability. Only ~60 actions need an explicit entry; the rest are ordinary and
     * default to [supported] — but ONLY if this app actually ships them.
     *
     * An id the app has never heard of fails **closed**. It reaches here from an imported bundle
     * written by a newer build or another app, or from a typo, and answering "Ready" for it meant the
     * editor offered it and the import review waved it through. [AutomationSensitivityRegistry.isKnown]
     * is the oracle rather than the metadata registry, because it is a static set with no
     * registration-order hazard: a capability query before `registerActionMetadata()` must not turn
     * every action unsupported.
     */
    fun get(actionId: String): ActionCapability = capabilities[actionId]
        ?: if (AutomationSensitivityRegistry.isKnown(actionId)) supported else unknownAction

    private val unknownAction = ActionCapability(
        CapabilityLevel.Unsupported,
        "This build does not know this action — it may come from a newer version. Re-export the bundle " +
            "from a build that has it, or remove the action.",
    )

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

    // Android 17+ restricts these to "app visible, or a while-in-use eligible foreground service, or the
    // alarm stream with exact-alarm access" — a CONDITION, not an impossibility, and this fork ships the
    // eligible foreground service plus the runtime check (AudioRuntimeEligibility) that enforces it.
    // Unsupported would make them un-addable even though they work; setup-gated is the honest level.
    internal fun audioOutputCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.outputCapabilityReason(reason))
        } else {
            ActionCapability(CapabilityLevel.Supported, reason)
        }

    private fun shizukuCapability(feature: String): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "$feature runs through Shizuku — install and start Shizuku, then grant this app access.", CapabilityRequirement.Shizuku, blocking = true)

    private fun dialogCapability(): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "Shows over other apps; from a background trigger it needs the \"display over other apps\" permission. Always works when run from the app.", CapabilityRequirement.Overlay)

    private fun accessibilityCapability(): ActionCapability =
        ActionCapability(CapabilityLevel.RequiresSetup, "Enable the 白い熊 自由作業盤 accessibility service in Android settings.", CapabilityRequirement.Accessibility, blocking = true)

    internal fun mediaKeyCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.mediaKeyCapabilityReason(reason))
        } else {
            ActionCapability(CapabilityLevel.Supported, reason)
        }

    internal fun volumeCapabilityForSdk(sdkInt: Int, reason: String): ActionCapability =
        if (AndroidAudioHardening.isRestricted(sdkInt)) {
            ActionCapability(CapabilityLevel.RequiresSetup, AndroidAudioHardening.volumeCapabilityReason(reason))
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
