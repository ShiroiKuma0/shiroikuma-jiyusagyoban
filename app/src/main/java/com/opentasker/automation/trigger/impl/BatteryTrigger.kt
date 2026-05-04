package com.opentasker.automation.trigger.impl

import com.opentasker.automation.core.TriggerDefinition
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig

/**
 * Battery trigger that matches battery level changes.
 * Supports filtering by level range and status (charging/discharging).
 */
class BatteryTrigger : TriggerDefinition {
    override val id = "battery"
    override val displayName = "Battery Level"

    override fun matches(event: AutomationEvent, config: TriggerConfig): Boolean {
        if (event !is AutomationEvent.BatteryEvent) return false

        // Check battery level range
        val minLevel = (config.config["minLevel"] as Number?)?.toInt() ?: 0
        val maxLevel = (config.config["maxLevel"] as Number?)?.toInt() ?: 100

        if (event.level !in minLevel..maxLevel) {
            return false
        }

        // Check battery status (charging/discharging/full)
        val expectedStatus = config.config["status"] as String?
        if (expectedStatus != null && expectedStatus.isNotBlank()) {
            if (event.status != expectedStatus) {
                return false
            }
        }

        return true
    }
}
