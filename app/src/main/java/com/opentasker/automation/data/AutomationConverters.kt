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

/**
 * Type converters for Room to handle complex data types via JSON serialization.
 */
class AutomationConverters {
    private val gson = Gson()

    // ========== AutomationRule ==========
    @TypeConverter
    fun fromAutomationRule(rule: AutomationRule): String = gson.toJson(rule)

    @TypeConverter
    fun toAutomationRule(json: String): AutomationRule = gson.fromJson(json, AutomationRule::class.java)

    // ========== TriggerConfig ==========
    @TypeConverter
    fun fromTriggerConfigList(list: List<TriggerConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toTriggerConfigList(json: String): List<TriggerConfig> {
        val type = object : TypeToken<List<TriggerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== ConstraintConfig ==========
    @TypeConverter
    fun fromConstraintConfigList(list: List<ConstraintConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toConstraintConfigList(json: String): List<ConstraintConfig> {
        val type = object : TypeToken<List<ConstraintConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== ConstraintGroup ==========
    @TypeConverter
    fun fromConstraintGroupList(list: List<ConstraintGroup>): String = gson.toJson(list)

    @TypeConverter
    fun toConstraintGroupList(json: String): List<ConstraintGroup> {
        val type = object : TypeToken<List<ConstraintGroup>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== ActionConfig ==========
    @TypeConverter
    fun fromActionConfigList(list: List<ActionConfig>): String = gson.toJson(list)

    @TypeConverter
    fun toActionConfigList(json: String): List<ActionConfig> {
        val type = object : TypeToken<List<ActionConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== Map<String, Any> ==========
    @TypeConverter
    fun fromMap(map: Map<String, Any>): String = gson.toJson(map)

    @TypeConverter
    fun toMap(json: String): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== ActionResult ==========
    @TypeConverter
    fun fromActionResult(result: ActionResult): String = gson.toJson(result)

    @TypeConverter
    fun toActionResult(json: String): ActionResult = gson.fromJson(json, ActionResult::class.java)

    @TypeConverter
    fun fromActionResultList(list: List<Pair<String, ActionResult>>): String = gson.toJson(list)

    @TypeConverter
    fun toActionResultList(json: String): List<Pair<String, ActionResult>> {
        val type = object : TypeToken<List<Pair<String, ActionResult>>>() {}.type
        return gson.fromJson(json, type)
    }

    // ========== Enums ==========
    @TypeConverter
    fun fromLogicalOperator(operator: LogicalOperator): String = operator.name

    @TypeConverter
    fun toLogicalOperator(name: String): LogicalOperator = LogicalOperator.valueOf(name)

    @TypeConverter
    fun fromExecutionStatus(status: ExecutionStatus): String = status.name

    @TypeConverter
    fun toExecutionStatus(name: String): ExecutionStatus = ExecutionStatus.valueOf(name)
}
