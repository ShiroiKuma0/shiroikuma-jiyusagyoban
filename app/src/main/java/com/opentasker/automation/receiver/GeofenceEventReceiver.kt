package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import android.location.LocationManager
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

            log("Geofence event: $eventType at ($latitude, $longitude)")
            automationEngine.onEvent(AutomationEvent.GeofenceEvent(latitude, longitude, eventType))
        } catch (e: Exception) {
            log("Error processing geofence event", e)
        }
    }
}
