package com.opentasker.core.contexts

import com.opentasker.app.R

object ScreenRecordingEventPresets {
    private val presets = listOf(
        EventContextPreset(
            id = "screen-recording-visible",
            labelRes = R.string.context_preset_screen_recording_visible,
            config = mapOf(
                "event" to ScreenRecordingContextEvents.EVENT_SCREEN_RECORDING,
                "state" to ScreenRecordingContextEvents.STATE_VISIBLE,
            ),
        ),
        EventContextPreset(
            id = "screen-recording-not-visible",
            labelRes = R.string.context_preset_screen_recording_not_visible,
            config = mapOf(
                "event" to ScreenRecordingContextEvents.EVENT_SCREEN_RECORDING,
                "state" to ScreenRecordingContextEvents.STATE_NOT_VISIBLE,
            ),
        ),
    )

    fun presetsFor(event: String): List<EventContextPreset> =
        if (event.trim().equals(ScreenRecordingContextEvents.EVENT_SCREEN_RECORDING, ignoreCase = true)) {
            presets
        } else {
            emptyList()
        }
}
