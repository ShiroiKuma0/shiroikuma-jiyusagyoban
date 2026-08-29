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
    Location,
    Bluetooth,
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
        "wallpaper.live" to ActionCapability(CapabilityLevel.RequiresSetup, "Silent only with Shizuku — shell holds the privileged permission the framework reserves for setting a live wallpaper. Without it the system preview opens for a confirming tap.", CapabilityRequirement.Shizuku),
        "ocr.models" to ActionCapability(CapabilityLevel.Supported, "Records where the OCR weight files are. Needs no permission beyond being able to read the folder."),
        "ocr.recognize" to ActionCapability(CapabilityLevel.Supported, "Reads the text in an image entirely on-device (PP-OCRv5); no permissions and no network. The image must be somewhere this app can read."),
        "ocr.article" to ActionCapability(CapabilityLevel.Supported, "Reads a scrolling screenshot into an HTML article, entirely on-device. No network. Needs to read the screenshots and write into the output folder."),
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
        "scene.gestures" to ActionCapability(CapabilityLevel.Supported, "Reads the scenes already stored on this device to list their gesture bindings; shows nothing and needs no permission."),
        "key.bindings" to ActionCapability(CapabilityLevel.Supported, "Reads the profiles already stored on this device to list what the physical keys are mapped to; shows nothing and needs no permission."),
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
        // Reads a fix through the framework LocationManager; the permission is the whole requirement.
        // Connects to the band over BLE. No pairing, no bonding and no scan — it is addressed by
        // MAC — so BLUETOOTH_CONNECT is the entire requirement.
        "band.charts" to ActionCapability(CapabilityLevel.Supported, "Opens the 健康 window on data already stored on this device. Needs no permission.", blocking = false),
        "band.sync" to ActionCapability(CapabilityLevel.RequiresSetup, "Reads the Hume Band's stored health history over Bluetooth — needs the Nearby devices (Bluetooth) permission.", CapabilityRequirement.Bluetooth, blocking = true),
        // Deliberately NOT worded like band.sync above. That entry says "no pairing, no bonding
        // and no scan — it is addressed by MAC", which is exactly false here: this band refuses
        // the RFCOMM channel outright until it is bonded.
        "huawei.sync" to ActionCapability(CapabilityLevel.RequiresSetup, "Reads the HUAWEI Band 11 Pro's stored health history over Bluetooth Classic RFCOMM — the band must be paired first, and it needs the Nearby devices (Bluetooth) permission.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.unpair" to ActionCapability(CapabilityLevel.RequiresSetup, "Forgets the HUAWEI band on this phone — clears our bind and the Bluetooth pairing. Needs the Nearby devices (Bluetooth) permission. Release the band on the band FIRST, or it deadlocks and only a factory reset recovers it.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.language" to ActionCapability(CapabilityLevel.RequiresSetup, "Sets the language and unit system shown on the paired HUAWEI band — needs the Nearby devices (Bluetooth) permission. It changes only what the band displays; no health data is read or written.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.files" to ActionCapability(CapabilityLevel.RequiresSetup, "Fetches the paired HUAWEI band's sleep and RR-interval files and writes them to disk — needs the Nearby devices (Bluetooth) permission. It reads the band and changes nothing on it.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.watchface" to ActionCapability(CapabilityLevel.RequiresSetup, "Installs a previously captured watch face on the paired HUAWEI band — needs the Nearby devices (Bluetooth) permission. It writes to the band, and the band verifies a digest before accepting.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.settings" to ActionCapability(CapabilityLevel.RequiresSetup, "Changes what the paired HUAWEI band records — continuous heart rate, SpO₂, truSleep and their alerts. Needs the Nearby devices (Bluetooth) permission.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.weather" to ActionCapability(CapabilityLevel.RequiresSetup, "Pushes weather to the paired HUAWEI band's display — needs the Nearby devices (Bluetooth) permission. It sends only what the task supplies; nothing is fetched from the network.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.gnss" to ActionCapability(CapabilityLevel.RequiresSetup, "Gives the paired HUAWEI band the satellite assistance data it cannot fetch itself, so a GPS fix takes seconds instead of minutes of cold search. Needs the Nearby devices (Bluetooth) permission. It serves files already on disk — it never fetches the URL the band asks for, which would make this app the band's general HTTP client.", CapabilityRequirement.Bluetooth, blocking = true),
        "ui.click" to ActionCapability(CapabilityLevel.RequiresSetup, "Presses a control in another app by the words written on it, using the accessibility service. It reads the on-screen labels of whatever app is in front — which is what makes it work over the lock screen, where a screenshot comes back blank.", CapabilityRequirement.Accessibility, blocking = true),
        "huawei.workouts" to ActionCapability(CapabilityLevel.RequiresSetup, "Reads the walks the band recorded and downloads their GPS tracks, writing a .gpx and the raw file to a directory — needs the Nearby devices (Bluetooth) permission. It reads from the band only; nothing is sent anywhere.", CapabilityRequirement.Bluetooth, blocking = true),
        "band.compare" to ActionCapability(CapabilityLevel.Supported, "Opens a window putting the two bands side by side. It reads what has already been synced and touches no device.", CapabilityRequirement.None),
        "huawei.probe" to ActionCapability(CapabilityLevel.RequiresSetup, "Asks the paired HUAWEI band which services and commands it supports, and writes the answer to a file. The census and the known count queries only read; the optional unknown-command sweep may set something on the band and is off by default. Needs the Nearby devices (Bluetooth) permission.", CapabilityRequirement.Bluetooth, blocking = true),
        "huawei.charts" to ActionCapability(CapabilityLevel.Supported, "Opens the 健康（Huawei） window on data already stored on this device. Needs no permission.", blocking = false),
        "huawei.board" to ActionCapability(CapabilityLevel.Supported, "Opens 健康 -- [727], the board every other band window is reached from. Needs no permission.", blocking = false),
        "huawei.pair" to ActionCapability(CapabilityLevel.RequiresSetup, "Pairs the HUAWEI Band 11 Pro with this phone and provisions it — needs the Nearby devices (Bluetooth) permission, and two confirmations that cannot be automated: one on the band, one on the phone.", CapabilityRequirement.Bluetooth, blocking = true),
        "band.scan" to ActionCapability(CapabilityLevel.RequiresSetup, "Listens for nearby Bluetooth devices to find the band's address — needs the Nearby devices (Bluetooth) permission.", CapabilityRequirement.Bluetooth, blocking = true),
        "location.get" to ActionCapability(CapabilityLevel.RequiresSetup, "Reads the device's position into variables — needs the Location permission.", CapabilityRequirement.Location, blocking = true),
        // Upstream 0.2.88. Android returns the last cached scan whether or not a fresh one was
        // accepted, so the permission is the whole gate the user can act on.
        "wifi.scan" to ActionCapability(CapabilityLevel.RequiresSetup, "Lists nearby access points into variables — needs Location, and Nearby Wi-Fi devices on Android 13+. Android rate-limits scans, so the result may be a cached one.", CapabilityRequirement.Location, blocking = true),
        // Upstream 0.2.88. Writes a secure setting through the Shizuku transport and reads it
        // back, because plenty of OEM builds accept the write and ignore it.
        "aod.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Always-on display is a protected secure setting — requires Shizuku, and not every build honours it.", CapabilityRequirement.Shizuku, blocking = true),
        // Pins its own network and transfers over it; no special access beyond the declared
        // CHANGE_NETWORK_STATE, and it never changes the system's default route.
        "net.speedtest.cancel" to ActionCapability(CapabilityLevel.Supported, "Aborts a running speed test immediately."),
        "net.speedtest" to ActionCapability(CapabilityLevel.Supported, "Measures download/upload throughput over a chosen transport (mobile or WiFi), time-boxed and byte-capped."),
        "audio.record.start" to ActionCapability(CapabilityLevel.RequiresSetup, "Records the microphone — needs the Microphone (RECORD_AUDIO) permission.", CapabilityRequirement.Microphone, blocking = true),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported 白い熊 自由作業盤 action."),
        "macrodroid.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported MacroDroid action could not be mapped to a supported 白い熊 自由作業盤 action."),
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
    fun get(actionId: String): ActionCapability = resolveDeclared(actionId)

    /**
     * The same resolution, reachable from an [com.opentasker.core.actions.ActionDefinition]'s default
     * capability lambda. Upstream routes every capability query through the catalogue; the fork keeps
     * the fail-closed table above as the single answer, so both entry points land here.
     */
    internal fun resolveDeclared(actionId: String): ActionCapability = capabilities[actionId]
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
            // The fork's registry classifies by CapabilityRequirement, not by upstream's label
            // resource, and it does not classify wol/app.archive at all — so upstream's two new
            // helpers alongside this one are deliberately not taken here.
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
