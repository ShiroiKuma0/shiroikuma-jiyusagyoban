package com.opentasker.automation.trigger.impl

import com.opentasker.automation.core.TriggerDefinition
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig

/**
 * WiFi trigger that matches WiFi connection/disconnection events.
 * Supports filtering by SSID pattern (regex).
 */
class WiFiTrigger : TriggerDefinition {
    override val id = "wifi"
    override val displayName = "WiFi Connected/Disconnected"

    override fun matches(event: AutomationEvent, config: TriggerConfig): Boolean {
        if (event !is AutomationEvent.WiFiEvent) return false

        // Check connected status
        val expectedConnected = config.config["connected"] as Boolean?
        if (expectedConnected != null && event.connected != expectedConnected) {
            return false
        }

        // Check SSID pattern (regex)
        val ssidPattern = config.config["ssid"] as String?
        if (ssidPattern != null && ssidPattern.isNotBlank()) {
            try {
                if (!event.ssid.matches(Regex(ssidPattern))) {
                    return false
                }
            } catch (e: Exception) {
                return false
            }
        }

        return true
    }
}
