package com.opentasker.automation.data.repository

import com.opentasker.automation.data.dao.ExecutionLogDao
import com.opentasker.automation.data.entity.ExecutionLogEntity
import com.opentasker.automation.model.ActionResult
import com.opentasker.automation.model.ExecutionLog
import com.opentasker.automation.model.ExecutionStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for execution logs.
 */
class ExecutionLogRepository(private val dao: ExecutionLogDao) {
    private val gson = Gson()

    suspend fun insert(log: ExecutionLog) {
        val entity = toEntity(log)
        dao.insert(entity)
    }

    suspend fun insertAll(logs: List<ExecutionLog>) {
        val entities = logs.map { toEntity(it) }
        dao.insertAll(entities)
    }

    suspend fun delete(log: ExecutionLog) {
        dao.delete(toEntity(log))
    }

    suspend fun deleteById(logId: String) {
        dao.deleteById(logId)
    }

    suspend fun getById(logId: String): ExecutionLog? {
        return dao.getById(logId)?.let { fromEntity(it) }
    }

    fun getByRuleId(ruleId: String): Flow<List<ExecutionLog>> {
        return dao.getByRuleId(ruleId).map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    suspend fun getByRuleIdLimited(ruleId: String, limit: Int = 50): List<ExecutionLog> {
        return dao.getByRuleIdLimited(ruleId, limit).map { fromEntity(it) }
    }

    fun getAll(): Flow<List<ExecutionLog>> {
        return dao.getAll().map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    suspend fun getAllLimited(limit: Int = 100): List<ExecutionLog> {
        return dao.getAllLimited(limit).map { fromEntity(it) }
    }

    fun getAllSince(since: Long): Flow<List<ExecutionLog>> {
        return dao.getAllSince(since).map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    fun getByStatus(status: ExecutionStatus): Flow<List<ExecutionLog>> {
        return dao.getByStatus(status.name).map { entities ->
            entities.map { fromEntity(it) }
        }
    }

    suspend fun deleteOlderThan(beforeTime: Long) {
        dao.deleteOlderThan(beforeTime)
    }

    suspend fun countByRuleId(ruleId: String): Int {
        return dao.countByRuleId(ruleId)
    }

    suspend fun getTotalCount(): Int {
        return dao.getTotalCount()
    }

    // ========== Serialization ==========

    private fun toEntity(log: ExecutionLog): ExecutionLogEntity {
        return ExecutionLogEntity(
            id = log.id,
            ruleId = log.ruleId,
            ruleName = log.ruleName,
            triggerId = log.triggerId,
            triggerType = log.triggerType,
            timestamp = log.timestamp,
            status = log.status.name,
            message = log.message,
            executionTimeMs = log.executionTimeMs,
            actionResultsJson = gson.toJson(log.actionResults)
        )
    }

    private fun fromEntity(entity: ExecutionLogEntity): ExecutionLog {
        val actionResults: List<Pair<String, ActionResult>> = if (entity.actionResultsJson != null) {
            val type = object : TypeToken<List<Pair<String, ActionResult>>>() {}.type
            try {
                gson.fromJson(entity.actionResultsJson, type) ?: emptyList()
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to deserialize action results for log ${entity.id}", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        val status = try {
            ExecutionStatus.valueOf(entity.status)
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Unknown status '${entity.status}', defaulting to FAILURE")
            ExecutionStatus.FAILURE
        }

        return ExecutionLog(
            id = entity.id,
            ruleId = entity.ruleId,
            ruleName = entity.ruleName,
            triggerId = entity.triggerId,
            triggerType = entity.triggerType,
            timestamp = entity.timestamp,
            status = status,
            message = entity.message,
            executionTimeMs = entity.executionTimeMs,
            actionResults = actionResults
        )
    }

    companion object {
        private const val TAG = "ExecutionLogRepository"
    }
}
