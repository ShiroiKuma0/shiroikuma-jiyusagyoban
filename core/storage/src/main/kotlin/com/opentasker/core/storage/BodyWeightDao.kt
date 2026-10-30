package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One body-weight reading, typed in by 白い熊 from the 健康 report's 今朝 card.
 *
 * Every weighing is its own row, keyed by the instant it was saved: the history is the point, and a
 * second weighing on one day is a reading too, not a correction of the first. Screens that show one
 * figure per day take that day's latest.
 */
@Entity(tableName = "body_weight", primaryKeys = ["epochMs"])
data class BodyWeightEntity(
    /** When it was recorded, UTC milliseconds. */
    val epochMs: Long,
    /** Kilograms, as typed. */
    val kg: Double,
)

@Dao
interface BodyWeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BodyWeightEntity)

    @Query("SELECT * FROM body_weight ORDER BY epochMs")
    suspend fun all(): List<BodyWeightEntity>

    /** Withdraw the readings of one span — the dialog's emptied field deletes the day's weighings. */
    @Query("DELETE FROM body_weight WHERE epochMs >= :fromMs AND epochMs < :toMs")
    suspend fun deleteBetween(fromMs: Long, toMs: Long)
}
