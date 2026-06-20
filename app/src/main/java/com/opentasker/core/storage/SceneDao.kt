package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement

@Entity("scenes")
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val elementsJson: String,
) {
    fun toDomain() = try {
        Scene(id, name, widthDp, heightDp, Json.decodeFromString(elementsJson))
    } catch (e: Exception) {
        AppLogger.error("SceneDao", "Failed to deserialize scene $id: ${e.message}", e)
        Scene(id, name, widthDp, heightDp, emptyList())
    }
}

fun Scene.toEntity() = SceneEntity(id, name, widthDp, heightDp, Json.encodeToString(elements))

@Dao
interface SceneDao {
    @Insert suspend fun insert(s: SceneEntity): Long
    @Update suspend fun update(s: SceneEntity)
    @Delete suspend fun delete(s: SceneEntity)
    @Query("SELECT * FROM scenes WHERE id = :id") suspend fun getById(id: Long): SceneEntity?
    @Query("SELECT * FROM scenes") suspend fun getAll(): List<SceneEntity>
    @Query("SELECT * FROM scenes") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<SceneEntity>>
}
