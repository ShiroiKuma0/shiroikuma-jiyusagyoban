package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application context. Matches when a specific app (or set of apps) is in the foreground.
 *
 * Config keys:
 *   - "apps": comma-separated package names (e.g., "com.android.chrome,com.spotify.music")
 *
 * Requires PACKAGE_USAGE_STATS permission.
 */
class ApplicationContextSource : ContextSource {
    override val type = "app"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, false))
        // TODO: Wire up UsageStatsManager polling + BroadcastReceiver for package state changes
        return state.asStateFlow()
    }
}

/**
 * State context. Matches device state predicates: battery level, charging, headphones, screen, etc.
 *
 * Config keys:
 *   - "battery_level": 0-100 (matches if battery >= value)
 *   - "charging": "true" or "false"
 *   - "headphones": "connected"
 *   - "screen": "on" or "off"
 *   - "wifi": "connected"
 */
class StateContextSource : ContextSource {
    override val type = "state"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, false))
        // TODO: Hook BroadcastReceivers for battery, headphones, screen, connectivity
        return state.asStateFlow()
    }
}

/**
 * Location context. Matches when device is inside a geofence.
 *
 * Config keys:
 *   - "lat": latitude
 *   - "lon": longitude
 *   - "radius_m": radius in meters
 */
class LocationContextSource : ContextSource {
    override val type = "location"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, false))
        // TODO: Wire up GeofencingClient + LocationCallback
        return state.asStateFlow()
    }
}

/**
 * Event context. Matches one-shot triggers.
 *
 * Config keys:
 *   - "event_type": "sms", "notification", "boot", "intent", ...
 *   - (event-specific config)
 */
class EventContextSource : ContextSource {
    override val type = "event"

    override fun events(app: Context): Flow<ContextEvent> {
        val state = MutableStateFlow(ContextEvent(type, false))
        // TODO: Register BroadcastReceivers / NotificationListenerService / AccessibilityService
        return state.asStateFlow()
    }
}
