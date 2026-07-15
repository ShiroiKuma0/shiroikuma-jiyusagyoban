package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.opentasker.core.model.Variable
import kotlinx.coroutines.flow.Flow

@Entity("variables")
data class VariableEntity(
    @PrimaryKey val name: String,
    val value: String,
    val isGlobal: Boolean,
    val isSecret: Boolean = false,
) {
    /** Plain-value mapping retained for non-secret fixtures; ciphertext never reaches the domain. */
    fun toDomain(): Variable {
        require(!isEffectivelySecret()) { "Secret variables must be decoded through VariableRepository." }
        return Variable(name, value, isGlobal)
    }
}

/** Plain-value mapping retained for non-secret import fixtures; secret rows must use VariableRepository. */
fun Variable.toEntity(): VariableEntity {
    require(!isSecret) { "Secret variables must be encoded through VariableRepository." }
    return VariableEntity(name, value, isGlobal, isSecret = false)
}

@Dao
interface VariableDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(v: VariableEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<VariableEntity>)
    @Update suspend fun update(v: VariableEntity)
    @Delete suspend fun delete(v: VariableEntity)
    @Query("DELETE FROM variables WHERE name = :name") suspend fun deleteByName(name: String)
    @Query("SELECT * FROM variables WHERE name = :name") suspend fun get(name: String): VariableEntity?
    @Query("SELECT * FROM variables") suspend fun getAll(): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1") suspend fun getAllGlobal(): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1 ORDER BY name") fun getAllGlobalAsFlow(): Flow<List<VariableEntity>>
}
