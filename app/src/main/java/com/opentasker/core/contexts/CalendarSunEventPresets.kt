package com.opentasker.core.contexts

import androidx.annotation.StringRes
import com.opentasker.app.R

data class EventContextPreset(
    val id: String,
    @get:StringRes val labelRes: Int,
    val config: Map<String, String>,
)

object CalendarSunEventPresets {
    private val calendarPresets = listOf(
        EventContextPreset(
            id = "calendar-during",
            labelRes = R.string.context_preset_during_meeting,
            config = mapOf("event" to "calendar", "state" to "during"),
        ),
        EventContextPreset(
            id = "calendar-15-before",
            labelRes = R.string.context_preset_15_before,
            config = mapOf("event" to "calendar", "state" to "upcoming", "beforeMinutes" to "15"),
        ),
        EventContextPreset(
            id = "calendar-30-before",
            labelRes = R.string.context_preset_30_before,
            config = mapOf("event" to "calendar", "state" to "upcoming", "beforeMinutes" to "30"),
        ),
        EventContextPreset(
            id = "calendar-all-day",
            labelRes = R.string.context_preset_all_day_busy,
            config = mapOf("event" to "calendar", "state" to "during", "allDay" to "true"),
        ),
    )

    private val sunrisePresets = sunPresets("sunrise")
    private val sunsetPresets = sunPresets("sunset")

    fun presetsFor(event: String): List<EventContextPreset> = when (event.trim().lowercase()) {
        "calendar" -> calendarPresets
        "sunrise" -> sunrisePresets
        "sunset" -> sunsetPresets
        else -> emptyList()
    }

    fun applyPreset(current: Map<String, String>, preset: EventContextPreset): Map<String, String> =
        current + preset.config

    private fun sunPresets(event: String): List<EventContextPreset> = listOf(
        EventContextPreset(
            id = "$event-at",
            labelRes = if (event == "sunrise") R.string.context_preset_at_sunrise else R.string.context_preset_at_sunset,
            config = mapOf("event" to event, "offsetMinutes" to "0", "windowMinutes" to "5"),
        ),
        EventContextPreset(
            id = "$event-before",
            labelRes = R.string.context_preset_30_before,
            config = mapOf("event" to event, "offsetMinutes" to "-30", "windowMinutes" to "10"),
        ),
        EventContextPreset(
            id = "$event-after",
            labelRes = R.string.context_preset_30_after,
            config = mapOf("event" to event, "offsetMinutes" to "30", "windowMinutes" to "10"),
        ),
    )
}
