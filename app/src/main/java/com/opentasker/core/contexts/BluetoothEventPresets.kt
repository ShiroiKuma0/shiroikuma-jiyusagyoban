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
    )

    fun presetsFor(event: String): List<EventContextPreset> = when (event.trim().lowercase()) {
        BluetoothContextEvents.EVENT_ALL_DISCONNECTED -> allPresets()
        else -> emptyList()
    }
}
