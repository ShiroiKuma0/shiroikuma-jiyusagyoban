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
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ContextSpec

@Entity("profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val enterTaskId: Long,
    val exitTaskId: Long?,
    val cooldownSec: Int,
    val contextsJson: String,
) {
    fun toDomain() = Profile(
        id, name, enabled, Json.decodeFromString(contextsJson), enterTaskId, exitTaskId, cooldownSec
    )
}

fun Profile.toEntity() = ProfileEntity(
    id, name, enabled, enterTaskId, exitTaskId, cooldownSec, Json.encodeToString(contexts)
)

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun getById(id: Long): ProfileEntity?
    @Query("SELECT * FROM profiles") suspend fun getAll(): List<ProfileEntity>
    @Query("SELECT * FROM profiles WHERE enabled = 1") suspend fun getAllEnabled(): List<ProfileEntity>
    @Query("SELECT * FROM profiles") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>
}
