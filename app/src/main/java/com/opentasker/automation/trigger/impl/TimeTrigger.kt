package com.opentasker.automation.trigger.impl

import com.opentasker.automation.core.TriggerDefinition
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig
import java.util.Calendar

/**
 * Time-based trigger using cron-like scheduling.
 * Matches TimeEvent against configured cron expression.
 */
class TimeTrigger : TriggerDefinition {
    override val id = "time"
    override val displayName = "Schedule (Time-based)"

    override fun matches(event: AutomationEvent, config: TriggerConfig): Boolean {
        if (event !is AutomationEvent.TimeEvent) return false

        val cronExpression = config.config["cron"] as String? ?: return false
        return evaluateCron(cronExpression, event.timestamp)
    }

    /**
     * Evaluate cron expression against timestamp.
     * Format: "minute hour dayOfMonth month dayOfWeek"
     * Example: "0 9 * * 1" = 9:00 AM every Monday
     * Wildcards: * = any value
     * Values: minute (0-59), hour (0-23), dayOfMonth (1-31), month (1-12), dayOfWeek (0-6, 0=Sunday)
     */
    private fun evaluateCron(cronExpression: String, timestamp: Long): Boolean {
        val parts = cronExpression.trim().split("\\s+".toRegex())
        if (parts.size != 5) return false

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp

        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-indexed
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // Calendar.DAY_OF_WEEK is 1-7, we want 0-6

        return matches(parts[0], minute) &&
                matches(parts[1], hour) &&
                matches(parts[2], dayOfMonth) &&
                matches(parts[3], month) &&
                matches(parts[4], dayOfWeek)
    }

    // Check if value matches cron field (exact numbers, wildcards, ranges, lists, steps)
    private fun matches(field: String, value: Int): Boolean {
        return when {
            field == "*" -> true
            field.contains(",") -> field.split(",").any { matches(it, value) }
            field.contains("/") -> {
                val parts = field.split("/", limit = 2)
                if (parts.size != 2) return false
                val (base, step) = parts
                val stepVal = step.toIntOrNull() ?: return false
                if (stepVal <= 0) return false
                val baseVal = if (base == "*") 0 else base.toIntOrNull() ?: return false
                (value - baseVal) % stepVal == 0
            }
            field.contains("-") -> {
                val parts = field.split("-", limit = 2)
                if (parts.size != 2) return false
                val min = parts[0].toIntOrNull() ?: return false
                val max = parts[1].toIntOrNull() ?: return false
                min <= max && value in min..max
            }
            else -> field.toIntOrNull() == value
        }
    }
}
