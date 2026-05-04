package com.opentasker.automation.constraint.impl

import com.opentasker.automation.core.ConstraintDefinition
import com.opentasker.automation.model.ConstraintConfig

/**
 * Time-based constraint that checks if current time is within a range.
 * Useful for restricting rule execution to specific hours.
 */
class TimeConstraint : ConstraintDefinition {
    override val id = "time_range"
    override val displayName = "Time Range"

    override suspend fun evaluate(config: ConstraintConfig): Boolean {
        val startHour = (config.config["startHour"] as Number?)?.toInt() ?: return false
        val endHour = (config.config["endHour"] as Number?)?.toInt() ?: return false

        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        
        return if (startHour <= endHour) {
            // Normal range (e.g., 9-17)
            currentHour in startHour..endHour
        } else {
            // Overnight range (e.g., 22-6)
            currentHour >= startHour || currentHour <= endHour
        }
    }
}
