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
            field.contains(",") -> value in field.split(",").map { it.toIntOrNull() ?: -1 }
            field.contains("-") -> {
                val (min, max) = field.split("-").map { it.toIntOrNull() ?: -1 }
                value in min..max
            }
            field.contains("/") -> {
                val (base, step) = field.split("/")
                val stepVal = step.toIntOrNull() ?: return false
                val baseVal = if (base == "*") 0 else base.toIntOrNull() ?: return false
                (value - baseVal) % stepVal == 0
            }
            else -> field.toIntOrNull() == value
        }
    }
}
