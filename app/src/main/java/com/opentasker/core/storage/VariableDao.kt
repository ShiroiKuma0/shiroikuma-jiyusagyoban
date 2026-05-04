package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.opentasker.core.model.Variable

@Entity("variables")
data class VariableEntity(
    @PrimaryKey val name: String,
    val value: String,
    val isGlobal: Boolean,
) {
    fun toDomain() = Variable(name, value, isGlobal)
}

fun Variable.toEntity() = VariableEntity(name, value, isGlobal)

@Dao
interface VariableDao {
    @Insert suspend fun insert(v: VariableEntity)
    @Update suspend fun update(v: VariableEntity)
    @Delete suspend fun delete(v: VariableEntity)
    @Query("SELECT * FROM variables WHERE name = :name") suspend fun get(name: String): VariableEntity?
    @Query("SELECT * FROM variables") suspend fun getAll(): List<VariableEntity>
    @Query("SELECT * FROM variables WHERE isGlobal = 1") suspend fun getAllGlobal(): List<VariableEntity>
}
