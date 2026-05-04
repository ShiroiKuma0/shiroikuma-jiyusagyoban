package com.opentasker.automation.data.repository

import com.opentasker.automation.data.dao.AutomationRuleDao
import com.opentasker.automation.data.entity.AutomationRuleEntity
import com.opentasker.automation.model.AutomationRule
import com.opentasker.automation.model.ExecutionMode
import com.google.gson.Gson
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for automation rules.
 * Abstracts database access and handles serialization/deserialization.
 */
class AutomationRuleRepository(private val dao: AutomationRuleDao) {
    private val gson = Gson()

    suspend fun insert(rule: AutomationRule) {
        val entity = toEntity(rule)
        dao.insert(entity)
    }

    suspend fun insertAll(rules: List<AutomationRule>) {
        val entities = rules.map { toEntity(it) }
        dao.insertAll(entities)
    }

    suspend fun update(rule: AutomationRule) {
        val entity = toEntity(rule)
        dao.update(entity)
    }

    suspend fun delete(rule: AutomationRule) {
        dao.delete(toEntity(rule))
    }

    suspend fun deleteById(ruleId: String) {
        dao.deleteById(ruleId)
    }

    suspend fun getById(ruleId: String): AutomationRule? {
        return dao.getById(ruleId)?.let { fromEntity(it) }
    }

    fun getByProfileId(profileId: String): Flow<List<AutomationRule>> {
        return dao.getByProfileId(profileId).map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    suspend fun getEnabledByProfileId(profileId: String): List<AutomationRule> {
        return dao.getEnabledByProfileId(profileId).map { fromEntity(it) }
    }

    suspend fun getAllEnabled(): List<AutomationRule> {
        return dao.getAllEnabled().map { fromEntity(it) }
    }

    fun getAll(): Flow<List<AutomationRule>> {
        return dao.getAll().map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean) {
        dao.setEnabled(ruleId, enabled)
    }

    suspend fun countByProfileId(profileId: String): Int {
        return dao.countByProfileId(profileId)
    }

    // ========== Serialization ==========

    private fun toEntity(rule: AutomationRule): AutomationRuleEntity {
        return AutomationRuleEntity(
            id = rule.id,
            name = rule.name,
            description = rule.description,
            enabled = rule.enabled,
            profileId = rule.profileId,
            ruleJson = gson.toJson(rule),
            executionMode = rule.executionMode.name,
            createdAt = rule.createdAt,
            updatedAt = rule.updatedAt
        )
    }

    private fun fromEntity(entity: AutomationRuleEntity): AutomationRule {
        return try {
            gson.fromJson(entity.ruleJson, AutomationRule::class.java) ?: corruptedRule(entity, "Null JSON")
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to deserialize rule ${entity.id}", e)
            corruptedRule(entity, e.message ?: "Malformed JSON")
        }
    }

    private fun corruptedRule(entity: AutomationRuleEntity, reason: String) = AutomationRule(
        id = entity.id,
        name = "${entity.name} [Corrupted]",
        description = reason,
        enabled = false,
        profileId = entity.profileId,
        triggers = emptyList(),
        actions = emptyList(),
        executionMode = runCatching { ExecutionMode.valueOf(entity.executionMode) }.getOrDefault(ExecutionMode.SEQUENTIAL),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt
    )

    companion object {
        private const val TAG = "AutomationRuleRepository"
    }
}
