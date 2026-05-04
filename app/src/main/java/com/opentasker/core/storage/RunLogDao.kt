package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.opentasker.core.model.RunLogEntry

@Entity("run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val taskName: String,
    val timestamp: Long,
    val durationMs: Long,
    val success: Boolean,
    val message: String,
) {
    fun toDomain() = RunLogEntry(id, taskId, taskName, timestamp, durationMs, success, message)
}

fun RunLogEntry.toEntity() = RunLogEntity(id, taskId, taskName, timestamp, durationMs, success, message)

@Dao
interface RunLogDao {
    @Insert suspend fun insert(e: RunLogEntity)
    @Query("SELECT * FROM run_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecent(): List<RunLogEntity>
    @Query("SELECT * FROM run_logs WHERE taskId = :taskId ORDER BY timestamp DESC LIMIT 50")
    suspend fun getByTask(taskId: Long): List<RunLogEntity>
    @Query("DELETE FROM run_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
