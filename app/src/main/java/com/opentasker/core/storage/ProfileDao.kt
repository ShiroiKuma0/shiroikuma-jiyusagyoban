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
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.ContextSpec

@Entity("profiles", indices = [Index(value = ["projectId", "name"], unique = true)])
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val enterTaskId: Long,
    val exitTaskId: Long?,
    val cooldownSec: Int,
    val contextsJson: String,
    val automationMode: String = AutomationMode.SINGLE.name,
    val projectId: Long? = null,
    val position: Int = 0,
    val enterTaskName: String = "",
    val exitTaskName: String = "",
    val requiresRiskAcknowledgement: Boolean = false,
    // Upstream's profile policy columns. The fork keeps its own nullable projectId above (NULL =
    // Unfiled, which upstream has no equivalent for), so upstream's non-null projectId line is not
    // taken here — only the policy fields it introduced alongside it.
    @androidx.room.ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val gracePeriodSec: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "'NEVER'") val lifetime: String = ProfileLifetime.NEVER.name,
    val expiresAtMs: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val lifetimeConsumed: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val maxActiveExecutions: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val burstLimit: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "'LOG'") val overflowPolicy: String = ProfileOverflowPolicy.LOG.name,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val fallbackTaskId: Long? = null,
) {
    fun toDomain(): Profile = toDomainDecodeResult().requireDecoded()

    fun toDomainDecodeResult(): StorageDecodeResult<Profile> {
        val mode = runCatching { AutomationMode.valueOf(automationMode) }.getOrDefault(AutomationMode.SINGLE)
        val contexts = runCatching { StorageJson.decodeFromString<List<ContextSpec>>(contextsJson) }
            .getOrElse { error ->
                return StorageDecodeResult(
                    value = Profile(id, name, enabled, emptyList(), enterTaskId, exitTaskId, cooldownSec, mode, projectId, position, enterTaskName, exitTaskName, requiresRiskAcknowledgement = requiresRiskAcknowledgement),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.PROFILE,
                        recordId = id,
                        recordName = name,
                        fieldName = "contextsJson",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }

        val profileLifetime = runCatching { ProfileLifetime.valueOf(lifetime) }
            .getOrDefault(ProfileLifetime.NEVER)
        val profileOverflowPolicy = runCatching { ProfileOverflowPolicy.valueOf(overflowPolicy) }
            .getOrDefault(ProfileOverflowPolicy.LOG)
        return StorageDecodeResult(
            value = Profile(
                id = id,
                name = name,
                enabled = enabled,
                contexts = contexts,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec,
                automationMode = mode,
                projectId = projectId,
                position = position,
                enterTaskName = enterTaskName,
                exitTaskName = exitTaskName,
                requiresRiskAcknowledgement = requiresRiskAcknowledgement,
                priority = priority,
                gracePeriodSec = gracePeriodSec,
                lifetime = profileLifetime,
                expiresAtMs = expiresAtMs,
                lifetimeConsumed = lifetimeConsumed,
                maxActiveExecutions = maxActiveExecutions,
                burstLimit = burstLimit,
                overflowPolicy = profileOverflowPolicy,
                fallbackTaskId = fallbackTaskId,
            ),
        )
    }
}

fun Profile.toEntity() = ProfileEntity(
    id = id,
    name = name,
    enabled = enabled,
    enterTaskId = enterTaskId,
    exitTaskId = exitTaskId,
    cooldownSec = cooldownSec,
    contextsJson = StorageJson.encodeToString(contexts),
    automationMode = automationMode.name,
    projectId = projectId,
    position = position,
    enterTaskName = enterTaskName,
    exitTaskName = exitTaskName,
    requiresRiskAcknowledgement = requiresRiskAcknowledgement,
    priority = priority,
    gracePeriodSec = gracePeriodSec,
    lifetime = lifetime.name,
    expiresAtMs = expiresAtMs,
    lifetimeConsumed = lifetimeConsumed,
    maxActiveExecutions = maxActiveExecutions,
    burstLimit = burstLimit,
    overflowPolicy = overflowPolicy.name,
    fallbackTaskId = fallbackTaskId,
)

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun getById(id: Long): ProfileEntity?
    @Query("SELECT * FROM profiles ORDER BY position, id") suspend fun getAll(): List<ProfileEntity>
    @Query("SELECT * FROM profiles WHERE enabled = 1 AND requiresRiskAcknowledgement = 0")
    suspend fun getAllEnabled(): List<ProfileEntity>
    @Query("SELECT * FROM profiles ORDER BY position, id") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>
    @Query("UPDATE profiles SET position = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Int)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM profiles") suspend fun nextPosition(): Int
}
