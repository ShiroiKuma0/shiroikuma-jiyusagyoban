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
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task

@Entity("tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priority: Int,
    val collisionMode: String,
    val actionsJson: String,
    val projectId: Long? = null,
) {
    fun toDomain() = try {
        Task(
            id, name, priority, CollisionMode.valueOf(collisionMode), Json.decodeFromString(actionsJson), projectId
        )
    } catch (e: Exception) {
        android.util.Log.e("TaskDao", "Failed to deserialize task $id: ${e.message}", e)
        // Return task with empty actions as fallback
        Task(id, name, priority, CollisionMode.ABORT_NEW, emptyList(), projectId)
    }
}

fun Task.toEntity() = TaskEntity(
    id, name, priority, collisionMode.name, Json.encodeToString(actions), projectId
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
}
