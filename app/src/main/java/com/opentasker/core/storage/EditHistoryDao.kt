package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity("edit_history")
data class EditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val previousJson: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Dao
interface EditHistoryDao {
    @Insert
    suspend fun insert(entry: EditHistoryEntity): Long

    @Query("SELECT * FROM edit_history WHERE entityType = :type AND entityId = :entityId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(type: String, entityId: Long): EditHistoryEntity?

    @Query("DELETE FROM edit_history WHERE id NOT IN (SELECT id FROM edit_history WHERE entityType = :type AND entityId = :entityId ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun pruneOld(type: String, entityId: Long, keep: Int = MAX_HISTORY_PER_ENTITY)

    @Query("DELETE FROM edit_history WHERE entityType = :type AND entityId = :entityId")
    suspend fun deleteFor(type: String, entityId: Long)

    @Query("DELETE FROM edit_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    companion object {
        const val MAX_HISTORY_PER_ENTITY = 5
        const val TYPE_PROFILE = "profile"
        const val TYPE_TASK = "task"
    }
}
