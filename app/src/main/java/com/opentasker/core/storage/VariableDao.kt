package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opentasker.core.model.Variable
import kotlinx.coroutines.flow.Flow

/**
 * A persisted variable. [projectId] selects the scope:
 *   - `0`  → **super-global** (`%ALLCAPS`), app-wide.
 *   - `>0` → **project-global** (`%MixedCase`), owned by that project.
 * Task-local (`%lowercase`) variables are never persisted, so they don't appear here.
 * The primary key is the (projectId, name) pair, so the same name can exist in different scopes.
 */
@Entity("variables", primaryKeys = ["projectId", "name"])
data class VariableEntity(
    val projectId: Long,
    val name: String,
    val value: String,
) {
    fun toDomain() = Variable(name, value, projectId)
}

fun Variable.toEntity() = VariableEntity(projectId, name, value)

const val SUPER_GLOBAL_PROJECT_ID = 0L

@Dao
interface VariableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(v: VariableEntity)
    @Query("DELETE FROM variables WHERE projectId = :projectId AND name = :name")
    suspend fun delete(projectId: Long, name: String)
    @Query("SELECT * FROM variables WHERE projectId = :projectId AND name = :name")
    suspend fun get(projectId: Long, name: String): VariableEntity?
    @Query("SELECT * FROM variables") suspend fun getAll(): List<VariableEntity>
    @Query("SELECT * FROM variables ORDER BY projectId, name") fun getAllAsFlow(): Flow<List<VariableEntity>>
}
