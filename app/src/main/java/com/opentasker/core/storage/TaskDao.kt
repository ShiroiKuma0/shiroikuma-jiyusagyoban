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
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import com.opentasker.core.model.DEFAULT_PROJECT_ID

@Entity("tasks", indices = [Index("projectId")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priority: Int,
    val collisionMode: String,
    val actionsJson: String,
    @androidx.room.ColumnInfo(defaultValue = "1") val projectId: Long = DEFAULT_PROJECT_ID,
) {
    fun toDomain(): Task = toDomainDecodeResult().requireDecoded()

    fun toDomainDecodeResult(): StorageDecodeResult<Task> {
        val mode = runCatching { CollisionMode.valueOf(collisionMode) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Task(id, name, priority, CollisionMode.ABORT_NEW, emptyList(), projectId),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.TASK,
                        recordId = id,
                        recordName = name,
                        fieldName = "collisionMode",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }

        val actions = runCatching { StorageJson.decodeFromString<List<ActionSpec>>(actionsJson) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Task(id, name, priority, mode, emptyList(), projectId),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.TASK,
                        recordId = id,
                        recordName = name,
                        fieldName = "actionsJson",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }

        return StorageDecodeResult(
            value = Task(id, name, priority, mode, actions, projectId),
        )
    }
}

fun Task.toEntity() = TaskEntity(
    id, name, priority, collisionMode.name, StorageJson.encodeToString(actions), projectId
)

@Dao
interface TaskDao {
    @Insert suspend fun insert(t: TaskEntity): Long
    @Update suspend fun update(t: TaskEntity)
    @Delete suspend fun delete(t: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun getById(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks") suspend fun getAll(): List<TaskEntity>
    @Query("SELECT * FROM tasks") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE name = :name LIMIT 1") suspend fun getByName(name: String): TaskEntity?

    /** See [ProfileDao.getByNameIgnoreCase] for the ASCII-folding caveat. */
    @Query("SELECT * FROM tasks WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByNameIgnoreCase(name: String): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks") suspend fun countAll(): Int
    @Query("UPDATE tasks SET projectId = :targetProjectId WHERE projectId = :sourceProjectId")
    suspend fun reassignProject(sourceProjectId: Long, targetProjectId: Long)
}
