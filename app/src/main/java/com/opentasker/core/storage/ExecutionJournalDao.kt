package com.opentasker.core.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Durable execution state. Unlike [com.opentasker.core.engine.ActiveExecutionRegistry], these
 * rows survive process death and contain only bounded execution metadata, never action arguments
 * or variable values.
 */
@Entity(
    tableName = "execution_journal",
    indices = [Index("state"), Index("updatedAtMs")],
)
data class ExecutionJournalEntity(
    @PrimaryKey val executionId: String,
    val taskId: Long,
    val taskName: String,
    val source: String,
    val sourceLabel: String?,
    val profileId: Long?,
    val replayOf: String?,
    val parentExecutionId: String?,
    val producer: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val lastStepIndex: Int?,
    val lastStepLabel: String?,
    val state: String,
    val terminalReason: String?,
    val terminalAtMs: Long?,
    @ColumnInfo(defaultValue = "0") val runLogWritten: Boolean = false,
)

@Dao
interface ExecutionJournalDao {
    /** Returns -1 when a command with this id was already journaled. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ExecutionJournalEntity): Long

    @Query("SELECT * FROM execution_journal WHERE executionId = :executionId LIMIT 1")
    suspend fun getByExecutionId(executionId: String): ExecutionJournalEntity?

    @Query(
        """
        UPDATE execution_journal
        SET updatedAtMs = :updatedAtMs,
            lastStepIndex = :stepIndex,
            lastStepLabel = :stepLabel
        WHERE executionId = :executionId
          AND state = 'ACTIVE'
        """
    )
    suspend fun recordStep(
        executionId: String,
        stepIndex: Int,
        stepLabel: String?,
        updatedAtMs: Long,
    ): Int

    /** A terminal transition is accepted only once, while the row is still ACTIVE. */
    @Query(
        """
        UPDATE execution_journal
        SET state = :state,
            terminalReason = :terminalReason,
            terminalAtMs = :terminalAtMs,
            updatedAtMs = :terminalAtMs
        WHERE executionId = :executionId
          AND state = 'ACTIVE'
        """
    )
    suspend fun markTerminal(
        executionId: String,
        state: String,
        terminalReason: String?,
        terminalAtMs: Long,
    ): Int

    @Query("SELECT * FROM execution_journal WHERE state = 'ACTIVE' ORDER BY startedAtMs, executionId")
    suspend fun active(): List<ExecutionJournalEntity>

    @Query(
        """
        SELECT * FROM execution_journal
        WHERE state != 'ACTIVE'
          AND runLogWritten = 0
        ORDER BY terminalAtMs, executionId
        LIMIT :limit
        """
    )
    suspend fun unloggedTerminal(limit: Int): List<ExecutionJournalEntity>

    @Query(
        """
        UPDATE execution_journal
        SET runLogWritten = 1,
            updatedAtMs = :updatedAtMs
        WHERE executionId = :executionId
          AND state != 'ACTIVE'
          AND runLogWritten = 0
        """
    )
    suspend fun markRunLogWritten(executionId: String, updatedAtMs: Long): Int

    @Query(
        """
        DELETE FROM execution_journal
        WHERE state != 'ACTIVE'
          AND runLogWritten = 1
          AND executionId NOT IN (
              SELECT executionId FROM execution_journal
              WHERE state != 'ACTIVE'
              ORDER BY updatedAtMs DESC, executionId DESC
              LIMIT :maxEntries
          )
        """
    )
    suspend fun pruneTerminal(maxEntries: Int): Int
}
