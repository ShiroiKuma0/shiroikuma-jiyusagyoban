package com.opentasker.core.contexts

data class EventContextPreset(
    val id: String,
    val label: String,
    val config: Map<String, String>,
)

object CalendarSunEventPresets {
    private val calendarPresets = listOf(
        EventContextPreset(
            id = "calendar-during",
            label = "During meeting",
            config = mapOf("event" to "calendar", "state" to "during"),
        ),
        EventContextPreset(
            id = "calendar-15-before",
            label = "15 min before",
            config = mapOf("event" to "calendar", "state" to "upcoming", "beforeMinutes" to "15"),
        ),
        EventContextPreset(
            id = "calendar-30-before",
            label = "30 min before",
            config = mapOf("event" to "calendar", "state" to "upcoming", "beforeMinutes" to "30"),
        ),
        EventContextPreset(
            id = "calendar-all-day",
            label = "All-day busy",
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
            label = "At $event",
            config = mapOf("event" to event, "offsetMinutes" to "0", "windowMinutes" to "5"),
        ),
        EventContextPreset(
            id = "$event-before",
            label = "30 min before",
            config = mapOf("event" to event, "offsetMinutes" to "-30", "windowMinutes" to "10"),
        ),
        EventContextPreset(
            id = "$event-after",
            label = "30 min after",
            config = mapOf("event" to event, "offsetMinutes" to "30", "windowMinutes" to "10"),
        ),
    )
}
