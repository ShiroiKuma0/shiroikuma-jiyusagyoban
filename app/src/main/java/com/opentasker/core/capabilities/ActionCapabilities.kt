package com.opentasker.core.capabilities

enum class CapabilityLevel {
    Supported,
    RequiresSetup,
    Unsupported,
}

data class ActionCapability(
    val level: CapabilityLevel,
    val reason: String,
) {
    val canAdd: Boolean
        get() = level != CapabilityLevel.Unsupported
}

object ActionCapabilityRegistry {
    private val supported = ActionCapability(CapabilityLevel.Supported, "Ready")

    private val capabilities = mapOf(
        "notify.show" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires notification permission on Android 13+."),
        "plugin.locale.fire" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires an installed Locale-compatible plugin; requests are dispatched only to an explicit package."),
        "wifi.toggle" to ActionCapability(CapabilityLevel.Unsupported, "Android 10+ blocks direct WiFi toggles for normal apps."),
        "bluetooth.toggle" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Bluetooth permission and may be limited on newer Android versions."),
        "brightness.set" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires Write Settings special access."),
        "volume.set" to ActionCapability(CapabilityLevel.RequiresSetup, "May be blocked by Do Not Disturb policy access."),
        "airplane.toggle" to ActionCapability(CapabilityLevel.Unsupported, "Airplane mode changes require system or device-owner privileges."),
        "mobile.toggle" to ActionCapability(CapabilityLevel.Unsupported, "Mobile data changes require carrier, system, or device-owner privileges."),
        "sms.send" to ActionCapability(CapabilityLevel.RequiresSetup, "Requires SMS permission and Play policy review."),
        "screenshot.take" to ActionCapability(CapabilityLevel.Unsupported, "Screenshots require MediaProjection consent or privileged shell access."),
        "sound.play" to ActionCapability(CapabilityLevel.Unsupported, "Direct media playback is not implemented yet."),
        "media.mute" to ActionCapability(CapabilityLevel.RequiresSetup, "May be blocked by Do Not Disturb policy access."),
        "tts.speak" to ActionCapability(CapabilityLevel.Unsupported, "Text-to-speech is not implemented yet."),
        "reboot" to ActionCapability(CapabilityLevel.Unsupported, "Reboot requires privileged device-owner or system app access."),
        "lock" to ActionCapability(CapabilityLevel.Unsupported, "Device lock requires configured device-admin support."),
        "screen.off" to ActionCapability(CapabilityLevel.Unsupported, "Screen-off requires privileged power management access."),
        "wake" to ActionCapability(CapabilityLevel.Unsupported, "Wake requires a foreground activity or privileged wake flow."),
        "tasker.unsupported" to ActionCapability(CapabilityLevel.Unsupported, "Imported Tasker action could not be mapped to a supported OpenTasker action."),
    )

    fun get(actionId: String): ActionCapability = capabilities[actionId] ?: supported
}
