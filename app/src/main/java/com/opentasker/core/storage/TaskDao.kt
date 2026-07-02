package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task

// Unique (projectId, name): a task name is unique within its project (SQLite treats NULL projectId —
// Unfiled — as distinct, so the editor's UI check covers Unfiled).
@Entity("tasks", indices = [Index(value = ["projectId", "name"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priority: Int,
    val collisionMode: String,
    val actionsJson: String,
    val projectId: Long? = null,
    val position: Int = 0,
    val iconPath: String? = null,
    val freezeBubble: Boolean = false,
) {
    fun toDomain(): Task {
        val result = toDomainDecodeResult()
        result.issue?.let { issue ->
            AppLogger.error("TaskDao", "Failed to deserialize task $id: ${issue.message}")
        }
        return result.value
    }

    fun toDomainDecodeResult(): StorageDecodeResult<Task> {
        val mode = runCatching { CollisionMode.valueOf(collisionMode) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Task(id, name, priority, CollisionMode.ABORT_NEW, emptyList(), projectId, position, iconPath, freezeBubble),
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
                    value = Task(id, name, priority, mode, emptyList(), projectId, position, iconPath, freezeBubble),
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
            value = Task(id, name, priority, mode, actions, projectId, position, iconPath, freezeBubble),
        )
    }
}

fun Task.toEntity() = TaskEntity(
    id, name, priority, collisionMode.name, StorageJson.encodeToString(actions), projectId, position, iconPath, freezeBubble
)

@Dao
interface TaskDao {
    @Insert suspend fun insert(t: TaskEntity): Long
    @Update suspend fun update(t: TaskEntity)
    @Delete suspend fun delete(t: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun getById(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks ORDER BY position, id") suspend fun getAll(): List<TaskEntity>
    @Query("SELECT * FROM tasks ORDER BY position, id") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE name = :name LIMIT 1") suspend fun getByName(name: String): TaskEntity?
    @Query("UPDATE tasks SET position = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Int)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tasks") suspend fun nextPosition(): Int
}
