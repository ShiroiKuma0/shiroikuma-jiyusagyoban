package com.opentasker.automation.trigger.impl

import com.opentasker.automation.core.TriggerDefinition
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig
import kotlin.math.sqrt

/**
 * Geofence trigger that matches location entry/exit events.
 * Supports filtering by lat/lon and radius.
 */
class GeofenceTrigger : TriggerDefinition {
    override val id = "geofence"
    override val displayName = "Geofence (Location)"

    override fun matches(event: AutomationEvent, config: TriggerConfig): Boolean {
        if (event !is AutomationEvent.GeofenceEvent) return false

        // Check event type (enter/exit)
        val expectedEventType = config.config["eventType"].asString()?.takeIf { it.isNotBlank() }
            ?: config.config["event"].asString()?.takeIf { it.isNotBlank() }
        if (expectedEventType != null && !event.eventType.equals(expectedEventType, ignoreCase = true)) {
            return false
        }

        // Check location within radius
        val hasLocationFilter = listOf("latitude", "longitude", "radiusMeters", "lat", "lon", "radius")
            .any { config.config.containsKey(it) }
        if (!hasLocationFilter) return true

        val centerLat = config.config["latitude"].asDouble() ?: config.config["lat"].asDouble() ?: return false
        val centerLon = config.config["longitude"].asDouble() ?: config.config["lon"].asDouble() ?: return false
        val radiusMeters = config.config["radiusMeters"].asDouble() ?: config.config["radius"].asDouble() ?: return false
        if (radiusMeters < 0) return false

        val distance = calculateDistance(
            event.latitude, event.longitude,
            centerLat, centerLon
        )
        if (distance > radiusMeters) {
            return false
        }

        return true
    }

    /**
     * Calculate distance between two coordinates in meters (Haversine formula).
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun Any?.asString(): String? = when (this) {
        null -> null
        is String -> trim()
        else -> toString()
    }

    private fun Any?.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }
}
