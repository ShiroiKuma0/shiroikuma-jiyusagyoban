package com.opentasker.core.contexts

import com.opentasker.app.R

/**
 * One preset, because the action is the part only the user knows.
 *
 * It fills in `event=broadcast` and leaves the action blank, which is the step people otherwise
 * have to learn from documentation that does not exist in the app.
 */
object BroadcastEventPresets {
    fun allPresets(): List<EventContextPreset> = listOf(
        EventContextPreset(
            id = "broadcast-received",
            labelRes = R.string.context_preset_broadcast_received,
            config = mapOf("event" to BroadcastContextEvents.EVENT_BROADCAST),
        ),
    )
}
