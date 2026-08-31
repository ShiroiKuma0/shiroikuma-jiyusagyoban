package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opentasker.core.model.Variable
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A persisted variable. [projectId] selects the scope:
 *   - `0`  → **super-global** (`%ALLCAPS`), app-wide.
 *   - `>0` → **project-global** (`%MixedCase`), owned by that project.
 * Task-local (`%lowercase`) variables are never persisted, so they don't appear here.
 * The primary key is the (projectId, name) pair, so the same name can exist in different scopes.
 *
 * Fork note: upstream's `indices = [Index("name"), Index("projectId")]` is deliberately not taken —
 * the fork's exported Room schema has never carried them, and adding an index is a schema change
 * Room's identity check would reject on every existing install. @Serializable IS taken: it is what
 * lets upstream's edit-history snapshot capture a deleted variable, and it changes no column.
 */
@Entity("variables", primaryKeys = ["projectId", "name"])
@Serializable
data class VariableEntity(
    val projectId: Long,
    val name: String,
    val value: String,
    val isSecret: Boolean = false,
) {
    /** Plain-value mapping retained for non-secret fixtures; ciphertext never reaches the domain. */
    fun toDomain(): Variable {
        require(!isEffectivelySecret()) { "Secret variables must be decoded through VariableRepository." }
        return Variable(name, value, projectId)
    }
}

/** Plain-value mapping retained for non-secret import fixtures; secret rows must use VariableRepository. */
fun Variable.toEntity(): VariableEntity {
    require(!isSecret) { "Secret variables must be encoded through VariableRepository." }
    return VariableEntity(projectId, name, value, isSecret = false)
}

const val SUPER_GLOBAL_PROJECT_ID = 0L

@Dao
interface VariableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(v: VariableEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertStrict(v: VariableEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<VariableEntity>)
    @Update suspend fun update(v: VariableEntity)
    @Delete suspend fun delete(v: VariableEntity)
    @Query("DELETE FROM variables WHERE projectId = :projectId AND name = :name")
    suspend fun delete(projectId: Long, name: String)
    /** Drop project-globals whose projectId matches NO current project (dangling after a project was
     *  deleted/re-created) — they're dead, frozen-stale, and unreachable. Swept at startup. */
    @Query("DELETE FROM variables WHERE projectId != 0 AND projectId NOT IN (SELECT id FROM projects)")
    suspend fun deleteDangling(): Int
    /** Drop stale super-global copies of engine event vars (`INTENT_*` / `NOTIF_*`). These are now threaded
     *  per-invocation (event-local) to the triggered task, so any persisted copy is dead residue that only
     *  clutters the global namespace. Swept at startup. */
    @Query("DELETE FROM variables WHERE projectId = 0 AND (name GLOB 'INTENT_*' OR name GLOB 'NOTIF_*')")
    suspend fun deleteStaleEventVars(): Int
    @Query("SELECT * FROM variables WHERE projectId = :projectId AND name = :name")
    suspend fun get(projectId: Long, name: String): VariableEntity?
    @Query("SELECT * FROM variables") suspend fun getAll(): List<VariableEntity>
    @Query("SELECT * FROM variables ORDER BY projectId, name") fun getAllAsFlow(): Flow<List<VariableEntity>>
}
