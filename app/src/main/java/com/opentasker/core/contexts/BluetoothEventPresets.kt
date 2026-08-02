package com.opentasker.core.contexts

import com.opentasker.app.R

object BluetoothEventPresets {
    fun allPresets(): List<EventContextPreset> = listOf(
        EventContextPreset(
            id = "bluetooth-all-disconnected",
            labelRes = R.string.context_preset_all_bluetooth_disconnected,
            config = mapOf(
                "event" to BluetoothContextEvents.EVENT_ALL_DISCONNECTED,
                "state" to BluetoothContextEvents.STATE_ALL_DISCONNECTED,
            ),
        ),
        EventContextPreset(
            id = "bluetooth-key-missing",
            labelRes = R.string.context_preset_bluetooth_key_missing,
            config = mapOf(
                "event" to BluetoothContextEvents.EVENT_KEY_MISSING,
                "state" to BluetoothContextEvents.STATE_KEY_MISSING,
            ),
        ),
        EventContextPreset(
            id = "bluetooth-encrypted",
            labelRes = R.string.context_preset_bluetooth_encrypted,
            config = mapOf(
                "event" to BluetoothContextEvents.EVENT_ENCRYPTION_CHANGE,
                "state" to BluetoothContextEvents.STATE_ENCRYPTED,
            ),
        ),
        EventContextPreset(
            id = "bluetooth-unencrypted",
            labelRes = R.string.context_preset_bluetooth_unencrypted,
            config = mapOf(
                "event" to BluetoothContextEvents.EVENT_ENCRYPTION_CHANGE,
                "state" to BluetoothContextEvents.STATE_UNENCRYPTED,
            ),
        ),
    )

    fun presetsFor(event: String): List<EventContextPreset> = when (event.trim().lowercase()) {
        BluetoothContextEvents.EVENT_ALL_DISCONNECTED -> allPresets()
        else -> emptyList()
    }
}
