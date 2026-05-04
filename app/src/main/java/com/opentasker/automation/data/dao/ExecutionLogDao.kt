package com.opentasker.automation.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opentasker.automation.data.entity.ExecutionLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for execution logs.
 */
@Dao
interface ExecutionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ExecutionLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ExecutionLogEntity>)

    @Delete
    suspend fun delete(log: ExecutionLogEntity)

    @Query("DELETE FROM execution_logs WHERE id = :logId")
    suspend fun deleteById(logId: String)

    @Query("SELECT * FROM execution_logs WHERE id = :logId")
    suspend fun getById(logId: String): ExecutionLogEntity?

    @Query("SELECT * FROM execution_logs WHERE ruleId = :ruleId ORDER BY timestamp DESC")
    fun getByRuleId(ruleId: String): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE ruleId = :ruleId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByRuleIdLimited(ruleId: String, limit: Int = 50): List<ExecutionLogEntity>

    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getAllLimited(limit: Int = 100): List<ExecutionLogEntity>

    @Query("SELECT * FROM execution_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getAllSince(since: Long): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE status = :status ORDER BY timestamp DESC")
    fun getByStatus(status: String): Flow<List<ExecutionLogEntity>>

    @Query("DELETE FROM execution_logs WHERE timestamp < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM execution_logs WHERE ruleId = :ruleId")
    suspend fun countByRuleId(ruleId: String): Int

    @Query("SELECT COUNT(*) FROM execution_logs")
    suspend fun getTotalCount(): Int
}
