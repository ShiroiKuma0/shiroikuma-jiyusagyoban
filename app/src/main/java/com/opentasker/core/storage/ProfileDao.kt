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
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.DEFAULT_PROJECT_ID

@Entity("profiles", indices = [Index("projectId")])
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val enterTaskId: Long,
    val exitTaskId: Long?,
    val cooldownSec: Int,
    val contextsJson: String,
    val automationMode: String = AutomationMode.SINGLE.name,
    val profileGroup: String? = null,
    val requiresRiskAcknowledgement: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "1") val projectId: Long = DEFAULT_PROJECT_ID,
) {
    fun toDomain(): Profile = toDomainDecodeResult().requireDecoded()

    fun toDomainDecodeResult(): StorageDecodeResult<Profile> {
        val mode = runCatching { AutomationMode.valueOf(automationMode) }.getOrDefault(AutomationMode.SINGLE)
        val contexts = runCatching { StorageJson.decodeFromString<List<ContextSpec>>(contextsJson) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Profile(
                        id,
                        name,
                        enabled,
                        emptyList(),
                        enterTaskId,
                        exitTaskId,
                        cooldownSec,
                        mode,
                        profileGroup,
                        requiresRiskAcknowledgement,
                        projectId,
                    ),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.PROFILE,
                        recordId = id,
                        recordName = name,
                        fieldName = "contextsJson",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }

        return StorageDecodeResult(
            value = Profile(
                id,
                name,
                enabled,
                contexts,
                enterTaskId,
                exitTaskId,
                cooldownSec,
                mode,
                profileGroup,
                requiresRiskAcknowledgement,
                projectId,
            ),
        )
    }
}

fun Profile.toEntity() = ProfileEntity(
    id,
    name,
    enabled,
    enterTaskId,
    exitTaskId,
    cooldownSec,
    StorageJson.encodeToString(contexts),
    automationMode.name,
    group,
    requiresRiskAcknowledgement,
    projectId,
)

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun getById(id: Long): ProfileEntity?
    @Query("SELECT * FROM profiles") suspend fun getAll(): List<ProfileEntity>
    @Query("SELECT * FROM profiles WHERE enabled = 1 AND requiresRiskAcknowledgement = 0")
    suspend fun getAllEnabled(): List<ProfileEntity>
    @Query("SELECT * FROM profiles") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>
    @Query("UPDATE profiles SET projectId = :targetProjectId WHERE projectId = :sourceProjectId")
    suspend fun reassignProject(sourceProjectId: Long, targetProjectId: Long)
}
