package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.opentasker.core.model.RunLogEntry
import kotlinx.coroutines.flow.Flow

@Entity("run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val taskName: String,
    val timestamp: Long,
    val durationMs: Long,
    val success: Boolean,
    val message: String,
    val source: String? = null,
    val sourceLabel: String? = null,
) {
    fun toDomain() = RunLogEntry(id, taskId, taskName, timestamp, durationMs, success, message, source, sourceLabel)
}

fun RunLogEntry.toEntity() = RunLogEntity(
    id = id,
    taskId = taskId,
    taskName = taskName,
    timestamp = timestamp,
    durationMs = durationMs,
    success = success,
    message = message,
    source = source,
    sourceLabel = sourceLabel,
)

@Dao
interface RunLogDao {
    @Insert suspend fun insert(e: RunLogEntity)
    @Query("SELECT * FROM run_logs ORDER BY timestamp DESC, id DESC LIMIT 100")
    suspend fun getRecent(): List<RunLogEntity>
    @Query("SELECT * FROM run_logs ORDER BY timestamp DESC, id DESC LIMIT 100")
    fun getRecentFlow(): Flow<List<RunLogEntity>>
    @Query("SELECT * FROM run_logs WHERE taskId = :taskId ORDER BY timestamp DESC, id DESC LIMIT 50")
    suspend fun getByTask(taskId: Long): List<RunLogEntity>
    @Query(
        """
        DELETE FROM run_logs
        WHERE timestamp < :minimumTimestamp
            OR id NOT IN (
                SELECT id FROM run_logs
                ORDER BY timestamp DESC, id DESC
                LIMIT :maxEntries
            )
        """
    )
    suspend fun pruneRetention(maxEntries: Int, minimumTimestamp: Long): Int
    @Query("SELECT COUNT(*) FROM run_logs")
    suspend fun count(): Int
}
