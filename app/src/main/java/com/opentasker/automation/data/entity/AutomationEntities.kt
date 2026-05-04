package com.opentasker.automation.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for automation rules.
 * Stores the complete rule configuration as JSON.
 */
@Entity(
    tableName = "automation_rules",
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["enabled"])
    ]
)
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val profileId: String,
    val ruleJson: String, // Complete AutomationRule serialized as JSON
    val executionMode: String, // "PARALLEL" or "SEQUENTIAL"
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Room entity for execution logs.
 * Persists every time a rule is evaluated/executed.
 */
@Entity(
    tableName = "execution_logs",
    indices = [
        Index(value = ["ruleId"]),
        Index(value = ["timestamp"]),
        Index(value = ["status"])
    ]
)
data class ExecutionLogEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val ruleName: String,
    val triggerId: String?,
    val triggerType: String?,
    val timestamp: Long,
    val status: String, // "SUCCESS", "SKIPPED", "FAILURE"
    val message: String, // Summary of what happened
    val executionTimeMs: Long,
    val actionResultsJson: String? // List<Pair<String, ActionResult>> as JSON
)
