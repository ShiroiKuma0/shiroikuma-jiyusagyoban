package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Context is a trigger condition. Mirrors Tasker's six context families.
 * The [config] map is type-specific; each ContextType.handler knows how to interpret it.
 */
@Serializable
data class ContextSpec(
    val type: ContextType,
    val config: Map<String, String> = emptyMap(),
    val invert: Boolean = false,
)

@Serializable
enum class ContextType {
    APPLICATION,   // foreground app(s)
    TIME,          // clock-based window
    DAY,           // weekly schedule
    LOCATION,      // geofence
    STATE,         // device state (battery, headphones, charging, screen, ...)
    EVENT,         // one-shot triggers (boot, notification, NFC, calendar, ...)
}
