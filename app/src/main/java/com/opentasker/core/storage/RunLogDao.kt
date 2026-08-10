package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.opentasker.core.model.RunLogEntry
import kotlinx.coroutines.flow.Flow

// timestamp is indexed because recent summaries, anchored keyset pages, and retention pruning all
// sort or bound the table by timestamp (with the primary key as the deterministic tie-breaker).
@Entity("run_logs", indices = [Index("timestamp")])
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
    val executionId: String? = null,
    val replayOf: String? = null,
    @ColumnInfo(defaultValue = "0") val held: Boolean = false,
    val heldPayload: String? = null,
    val heldPolicy: String? = null,
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
) {
    fun toDomain() = RunLogEntry(
        id = id,
        taskId = taskId,
        taskName = taskName,
        timestamp = timestamp,
        durationMs = durationMs,
        success = success,
        message = message,
        source = source,
        sourceLabel = sourceLabel,
        executionId = executionId,
        replayOf = replayOf,
        held = held,
        heldPayload = heldPayload,
        heldPolicy = heldPolicy,
        starred = starred,
    )
}

data class RunLogKey(
    val timestamp: Long,
    val id: Long,
)

data class RunLogTaskOption(
    val taskId: Long,
    val taskName: String,
)

enum class RunLogStatusQuery { ALL, SUCCEEDED, FAILED, SKIPPED, CANCELLED, HELD }

data class RunLogQuery(
    val status: RunLogStatusQuery = RunLogStatusQuery.ALL,
    val taskId: Long? = null,
    val minimumTimestamp: Long? = null,
    val maximumTimestamp: Long? = null,
    /** Escaped for SQLite LIKE by [escapeRunLogLikeQuery]. */
    val escapedSearch: String = "",
)

fun escapeRunLogLikeQuery(query: String): String = query.trim()
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

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
    executionId = executionId,
    replayOf = replayOf,
    held = held,
    heldPayload = heldPayload,
    heldPolicy = heldPolicy,
    starred = starred,
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
    @Query("SELECT * FROM run_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RunLogEntity?
    @Query("UPDATE run_logs SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)
    @Query(
        """
        DELETE FROM run_logs
        WHERE held = 0
          AND starred = 0
          AND (
            timestamp < :minimumTimestamp
            OR id NOT IN (
                SELECT id FROM run_logs
                WHERE held = 0 AND starred = 0
                ORDER BY timestamp DESC, id DESC
                LIMIT :maxEntries
            )
          )
        """
    )
    suspend fun pruneRetention(maxEntries: Int, minimumTimestamp: Long): Int
    @Query("SELECT COUNT(*) FROM run_logs")
    suspend fun count(): Int

    @Query(
        """
        SELECT timestamp, id FROM run_logs
        WHERE id <= :snapshotMaxId
          AND (:taskId IS NULL OR taskId = :taskId)
          AND (:minimumTimestamp IS NULL OR timestamp >= :minimumTimestamp)
          AND (:maximumTimestamp IS NULL OR timestamp <= :maximumTimestamp)
          AND (
            :escapedSearch = ''
            OR taskName LIKE '%' || :escapedSearch || '%' ESCAPE '\'
            OR message LIKE '%' || :escapedSearch || '%' ESCAPE '\'
          )
          AND (
            :status = 'ALL'
            OR (:status = 'SKIPPED' AND instr(lower(message), 'decision: skipped') > 0)
            OR (:status = 'CANCELLED' AND instr(lower(message), 'decision: cancelled') > 0)
            OR (:status = 'HELD' AND held = 1)
            OR (:status = 'SUCCEEDED' AND success = 1
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
            OR (:status = 'FAILED' AND success = 0
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
          )
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun newestMatchingKey(
        status: String,
        taskId: Long?,
        minimumTimestamp: Long?,
        maximumTimestamp: Long?,
        escapedSearch: String,
        snapshotMaxId: Long,
    ): RunLogKey?

    @Query(
        """
        SELECT COUNT(*) FROM run_logs
        WHERE id <= :snapshotMaxId
          AND (:taskId IS NULL OR taskId = :taskId)
          AND (:minimumTimestamp IS NULL OR timestamp >= :minimumTimestamp)
          AND (:maximumTimestamp IS NULL OR timestamp <= :maximumTimestamp)
          AND (timestamp < :anchorTimestamp OR (timestamp = :anchorTimestamp AND id <= :anchorId))
          AND (
            :escapedSearch = ''
            OR taskName LIKE '%' || :escapedSearch || '%' ESCAPE '\'
            OR message LIKE '%' || :escapedSearch || '%' ESCAPE '\'
          )
          AND (
            :status = 'ALL'
            OR (:status = 'SKIPPED' AND instr(lower(message), 'decision: skipped') > 0)
            OR (:status = 'CANCELLED' AND instr(lower(message), 'decision: cancelled') > 0)
            OR (:status = 'HELD' AND held = 1)
            OR (:status = 'SUCCEEDED' AND success = 1
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
            OR (:status = 'FAILED' AND success = 0
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
          )
        """
    )
    suspend fun countMatchingAtAnchor(
        status: String,
        taskId: Long?,
        minimumTimestamp: Long?,
        maximumTimestamp: Long?,
        escapedSearch: String,
        anchorTimestamp: Long,
        anchorId: Long,
        snapshotMaxId: Long,
    ): Int

    @Query(
        """
        SELECT * FROM run_logs
        WHERE id <= :snapshotMaxId
          AND (:taskId IS NULL OR taskId = :taskId)
          AND (:minimumTimestamp IS NULL OR timestamp >= :minimumTimestamp)
          AND (:maximumTimestamp IS NULL OR timestamp <= :maximumTimestamp)
          AND (timestamp < :anchorTimestamp OR (timestamp = :anchorTimestamp AND id <= :anchorId))
          AND (
            :beforeTimestamp IS NULL
            OR timestamp < :beforeTimestamp
            OR (timestamp = :beforeTimestamp AND id < :beforeId)
          )
          AND (
            :escapedSearch = ''
            OR taskName LIKE '%' || :escapedSearch || '%' ESCAPE '\'
            OR message LIKE '%' || :escapedSearch || '%' ESCAPE '\'
          )
          AND (
            :status = 'ALL'
            OR (:status = 'SKIPPED' AND instr(lower(message), 'decision: skipped') > 0)
            OR (:status = 'CANCELLED' AND instr(lower(message), 'decision: cancelled') > 0)
            OR (:status = 'HELD' AND held = 1)
            OR (:status = 'SUCCEEDED' AND success = 1
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
            OR (:status = 'FAILED' AND success = 0
                AND held = 0
                AND instr(lower(message), 'decision: skipped') = 0
                AND instr(lower(message), 'decision: cancelled') = 0)
          )
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getPageAtAnchor(
        status: String,
        taskId: Long?,
        minimumTimestamp: Long?,
        maximumTimestamp: Long?,
        escapedSearch: String,
        anchorTimestamp: Long,
        anchorId: Long,
        beforeTimestamp: Long?,
        beforeId: Long?,
        snapshotMaxId: Long,
        limit: Int,
    ): List<RunLogEntity>

    @Query(
        """
        SELECT taskId, taskName FROM run_logs
        WHERE id IN (SELECT MAX(id) FROM run_logs GROUP BY taskId)
        ORDER BY taskName COLLATE NOCASE, taskId
        """
    )
    fun getTaskOptionsFlow(): Flow<List<RunLogTaskOption>>

    @Query("SELECT MIN(timestamp) FROM run_logs")
    suspend fun oldestTimestamp(): Long?

    @Query(
        """
        SELECT COUNT(*) FROM run_logs
        WHERE held = 0
          AND starred = 0
          AND (
            timestamp < :minimumTimestamp
            OR id NOT IN (
                SELECT id FROM run_logs
                WHERE held = 0 AND starred = 0
                ORDER BY timestamp DESC, id DESC
                LIMIT :maxEntries
            )
          )
        """
    )
    suspend fun countPrunable(maxEntries: Int, minimumTimestamp: Long): Int

    @Query("SELECT MAX(id) FROM run_logs")
    suspend fun maximumId(): Long?
}
