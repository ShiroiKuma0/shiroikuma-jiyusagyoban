package com.opentasker.automation.trigger.impl

import com.opentasker.automation.core.TriggerDefinition
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig

/**
 * App open/close trigger that matches app events.
 * Matches when a specific app is opened or closed.
 */
class AppOpenTrigger : TriggerDefinition {
    override val id = "app_open"
    override val displayName = "App Opened/Closed"

    override fun matches(event: AutomationEvent, config: TriggerConfig): Boolean {
        if (event !is AutomationEvent.AppEvent) return false

        // Check package name
        val expectedPackage = config.config["packageName"] as String? ?: return false
        if (event.packageName != expectedPackage) {
            return false
        }

        // Check opened/closed status
        val expectedOpened = config.config["opened"] as Boolean?
        if (expectedOpened != null && event.opened != expectedOpened) {
            return false
        }

        return true
    }
}
