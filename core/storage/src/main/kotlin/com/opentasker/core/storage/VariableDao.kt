package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import com.opentasker.core.model.Variable
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import kotlinx.coroutines.flow.Flow

@Entity("variables", primaryKeys = ["projectId", "name"], indices = [Index("name"), Index("projectId")])
data class VariableEntity(
    val name: String,
    val value: String,
    val isGlobal: Boolean,
    val isSecret: Boolean = false,
    val projectId: Long = DEFAULT_PROJECT_ID,
) {
    /** Plain-value mapping retained for non-secret fixtures; ciphertext never reaches the domain. */
    fun toDomain(): Variable {
        require(!isEffectivelySecret()) { "Secret variables must be decoded through VariableRepository." }
        return Variable(name, value, isGlobal, projectId = projectId)
    }
}

/** Plain-value mapping retained for non-secret import fixtures; secret rows must use VariableRepository. */
fun Variable.toEntity(): VariableEntity {
    require(!isSecret) { "Secret variables must be encoded through VariableRepository." }
    return VariableEntity(name, value, isGlobal, isSecret = false, projectId = projectId)
}

@Dao
interface VariableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(v: VariableEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<VariableEntity>)
    @Upsert suspend fun upsert(v: VariableEntity)
    @Upsert suspend fun upsertAll(values: List<VariableEntity>)
    @Update suspend fun update(v: VariableEntity)
    @Delete suspend fun delete(v: VariableEntity)
    @Query("DELETE FROM variables WHERE name = :name AND projectId = 1") suspend fun deleteByName(name: String)
    @Query("DELETE FROM variables WHERE name = :name AND projectId = :projectId") suspend fun deleteByNameInProject(name: String, projectId: Long)
    @Query("SELECT * FROM variables WHERE name = :name AND projectId = 1") suspend fun get(name: String): VariableEntity?
    @Query("SELECT * FROM variables WHERE name = :name AND projectId = :projectId") suspend fun getInProject(name: String, projectId: Long): VariableEntity?
    @Query("SELECT * FROM variables") suspend fun getAll(): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE projectId = :projectId") suspend fun getAllInProject(projectId: Long): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 AND projectId = 1") suspend fun getAllGlobal(): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 AND projectId = :projectId") suspend fun getAllGlobalInProject(projectId: Long): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 AND projectId = 1 ORDER BY name") fun getAllGlobalAsFlow(): Flow<List<VariableEntity>>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 AND projectId = :projectId ORDER BY name") fun getAllGlobalAsFlowInProject(projectId: Long): Flow<List<VariableEntity>>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 ORDER BY projectId, name") fun getAllGlobalAsFlowAll(): Flow<List<VariableEntity>>
    @Query("SELECT COUNT(*) FROM variables WHERE projectId = :projectId AND name = :name") suspend fun countInProject(projectId: Long, name: String): Int
    @Query("DELETE FROM variables WHERE projectId = :projectId") suspend fun deleteAllInProject(projectId: Long)
}
