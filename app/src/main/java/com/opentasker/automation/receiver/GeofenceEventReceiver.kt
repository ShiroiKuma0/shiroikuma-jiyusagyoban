package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import com.opentasker.automation.model.AutomationEvent

/**
 * Geofence transition event receiver.
 * Dispatches GeofenceEvent when user enters/exits geofence.
 * Works with Google Play Services geofence API.
 */
class GeofenceEventReceiver : AutomationBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        try {
            val latitude = intent.getDoubleExtra("latitude", 0.0)
            val longitude = intent.getDoubleExtra("longitude", 0.0)
            val eventType = intent.getStringExtra("eventType") ?: "enter" // enter, exit, dwell
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                log("Ignoring invalid geofence coordinates: $latitude, $longitude")
                return
            }
            if (eventType !in allowedEventTypes) {
                log("Ignoring invalid geofence event type: $eventType")
                return
            }

            log("Geofence event: $eventType at ($latitude, $longitude)")
            automationEngine.onEvent(AutomationEvent.GeofenceEvent(latitude, longitude, eventType))
        } catch (e: Exception) {
            log("Error processing geofence event", e)
        }
    }

    companion object {
        private val allowedEventTypes = setOf("enter", "exit", "dwell")
    }
}
