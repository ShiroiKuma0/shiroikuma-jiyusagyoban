package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.serialization.encodeToString
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.DEFAULT_PROJECT_ID

@Entity("scenes", indices = [Index("projectId")])
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val elementsJson: String,
    @androidx.room.ColumnInfo(defaultValue = "1") val projectId: Long = DEFAULT_PROJECT_ID,
) {
    fun toDomain(): Scene = toDomainDecodeResult().requireDecoded()

    fun toDomainDecodeResult(): StorageDecodeResult<Scene> {
        val elements = runCatching { StorageJson.decodeFromString<List<SceneElement>>(elementsJson) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Scene(id, name, widthDp, heightDp, emptyList(), projectId),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.SCENE,
                        recordId = id,
                        recordName = name,
                        fieldName = "elementsJson",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }
        return StorageDecodeResult(value = Scene(id, name, widthDp, heightDp, elements, projectId))
    }
}

fun Scene.toEntity() = SceneEntity(id, name, widthDp, heightDp, StorageJson.encodeToString(elements), projectId)

@Dao
interface SceneDao {
    @Insert suspend fun insert(s: SceneEntity): Long
    @Update suspend fun update(s: SceneEntity)
    @Delete suspend fun delete(s: SceneEntity)
    @Query("SELECT * FROM scenes WHERE id = :id") suspend fun getById(id: Long): SceneEntity?
    @Query("SELECT * FROM scenes") suspend fun getAll(): List<SceneEntity>
    @Query("SELECT * FROM scenes") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<SceneEntity>>
    @Query("UPDATE scenes SET projectId = :targetProjectId WHERE projectId = :sourceProjectId")
    suspend fun reassignProject(sourceProjectId: Long, targetProjectId: Long)
}
