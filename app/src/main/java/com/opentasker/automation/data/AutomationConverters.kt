package com.opentasker.automation.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
import com.opentasker.automation.model.AutomationRule
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.ExecutionStatus
import com.opentasker.automation.model.LogicalOperator
import com.opentasker.automation.model.TriggerConfig
import com.opentasker.core.logging.AppLogger
import java.util.UUID

/**
 * Type converters for Room to handle complex data types via JSON serialization.
 */
class AutomationConverters {
    private val gson = Gson()

    // ========== AutomationRule ==========
    @TypeConverter
    fun fromAutomationRule(rule: AutomationRule): String = gson.toJson(rule)

    @TypeConverter
    fun toAutomationRule(json: String): AutomationRule = try {
        if (json.isBlank()) {
            corruptedRule("Empty rule JSON")
        } else {
            gson.fromJson(json, AutomationRule::class.java) ?: corruptedRule("Null rule JSON")
        }
    } catch (e: Exception) {
        AppLogger.error(TAG, "Failed to deserialize automation rule", e)
        corruptedRule(e.message ?: "Malformed rule JSON")
    }

    // ========== TriggerConfig ==========
    @TypeConverter
    fun fromTriggerConfigList(list: List<TriggerConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toTriggerConfigList(json: String): List<TriggerConfig> {
        val type = object : TypeToken<List<TriggerConfig>>() {}.type
        return try {
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize trigger config list", e)
            emptyList()
        }
    }

    // ========== ConstraintConfig ==========
    @TypeConverter
    fun fromConstraintConfigList(list: List<ConstraintConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toConstraintConfigList(json: String): List<ConstraintConfig> {
        val type = object : TypeToken<List<ConstraintConfig>>() {}.type
        return try {
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize constraint config list", e)
            emptyList()
        }
    }

    // ========== ConstraintGroup ==========
    @TypeConverter
    fun fromConstraintGroupList(list: List<ConstraintGroup>): String = gson.toJson(list)

    @TypeConverter
    fun toConstraintGroupList(json: String): List<ConstraintGroup> {
        val type = object : TypeToken<List<ConstraintGroup>>() {}.type
        return try {
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize constraint group list", e)
            emptyList()
        }
    }

    // ========== ActionConfig ==========
    @TypeConverter
    fun fromActionConfigList(list: List<ActionConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toActionConfigList(json: String): List<ActionConfig> {
        val type = object : TypeToken<List<ActionConfig>>() {}.type
        return try {
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize action config list", e)
            emptyList()
        }
    }

    // ========== Map<String, Any> ==========
    @TypeConverter
    fun fromMap(map: Map<String, Any>): String = gson.toJson(map)

    @TypeConverter
    fun toMap(json: String): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return try {
            if (json.isBlank()) emptyMap() else gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize map", e)
            emptyMap()
        }
    }

    // ========== ActionResult ==========
    @TypeConverter
    fun fromActionResult(result: ActionResult): String = gson.toJson(result)

    @TypeConverter
    fun toActionResult(json: String): ActionResult = try {
        if (json.isBlank()) {
            corruptedActionResult("Empty action result JSON")
        } else {
            gson.fromJson(json, ActionResult::class.java) ?: corruptedActionResult("Null action result JSON")
        }
    } catch (e: Exception) {
        AppLogger.error(TAG, "Failed to deserialize action result", e)
        corruptedActionResult(e.message ?: "Malformed action result JSON")
    }

    @TypeConverter
    fun fromActionResultList(list: List<Pair<String, ActionResult>>): String = gson.toJson(list)

    @TypeConverter
    fun toActionResultList(json: String): List<Pair<String, ActionResult>> {
        val type = object : TypeToken<List<Pair<String, ActionResult>>>() {}.type
        return try {
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize action result list", e)
            emptyList()
        }
    }

    // ========== Enums ==========
    @TypeConverter
    fun fromLogicalOperator(operator: LogicalOperator): String = operator.name

    @TypeConverter
    fun toLogicalOperator(name: String): LogicalOperator = try {
        LogicalOperator.valueOf(name)
    } catch (e: Exception) {
        AppLogger.warn(TAG, "Unknown logical operator '$name', defaulting to AND")
        LogicalOperator.AND
    }

    @TypeConverter
    fun fromExecutionStatus(status: ExecutionStatus): String = status.name

    @TypeConverter
    fun toExecutionStatus(name: String): ExecutionStatus = try {
        ExecutionStatus.valueOf(name)
    } catch (e: Exception) {
        AppLogger.warn(TAG, "Unknown execution status '$name', defaulting to FAILURE")
        ExecutionStatus.FAILURE
    }

    private fun corruptedRule(reason: String) = AutomationRule(
        id = UUID.randomUUID().toString(),
        name = "[Corrupted rule]",
        description = reason,
        enabled = false,
        profileId = "",
        triggers = emptyList(),
        actions = emptyList()
    )

    private fun corruptedActionResult(reason: String) = ActionResult(
        success = false,
        message = "Corrupted action result: $reason",
        executionTimeMs = 0
    )

    companion object {
        private const val TAG = "AutomationConverters"
    }
}
