package com.opentasker.automation.trigger.impl

import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceTriggerTest {
    @Test
    fun stringBackedLocationConfigMatchesWithinRadius() {
        val config = TriggerConfig(type = "geofence").apply {
            config = mapOf(
                "eventType" to "enter",
                "latitude" to "40.7580",
                "longitude" to "-73.9855",
                "radiusMeters" to "150",
            )
        }

        assertTrue(
            GeofenceTrigger().matches(
                AutomationEvent.GeofenceEvent(40.7581, -73.9856, "enter"),
                config,
            )
        )
    }

    @Test
    fun eventTypeOnlyConfigDoesNotRequireRadius() {
        val config = TriggerConfig(type = "geofence").apply {
            config = mapOf("eventType" to "exit")
        }

        assertTrue(
            GeofenceTrigger().matches(
                AutomationEvent.GeofenceEvent(40.0, -73.0, "exit"),
                config,
            )
        )
        assertFalse(
            GeofenceTrigger().matches(
                AutomationEvent.GeofenceEvent(40.0, -73.0, "enter"),
                config,
            )
        )
    }
}
