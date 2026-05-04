package com.opentasker.automation.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opentasker.automation.data.entity.AutomationRuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for automation rules.
 */
@Dao
interface AutomationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AutomationRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<AutomationRuleEntity>)

    @Update
    suspend fun update(rule: AutomationRuleEntity)

    @Delete
    suspend fun delete(rule: AutomationRuleEntity)

    @Query("DELETE FROM automation_rules WHERE id = :ruleId")
    suspend fun deleteById(ruleId: String)

    @Query("SELECT * FROM automation_rules WHERE id = :ruleId")
    suspend fun getById(ruleId: String): AutomationRuleEntity?

    @Query("SELECT * FROM automation_rules WHERE profileId = :profileId ORDER BY name ASC")
    fun getByProfileId(profileId: String): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE profileId = :profileId AND enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledByProfileId(profileId: String): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun getAllEnabled(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules ORDER BY name ASC")
    fun getAll(): Flow<List<AutomationRuleEntity>>

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :ruleId")
    suspend fun setEnabled(ruleId: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM automation_rules WHERE profileId = :profileId")
    suspend fun countByProfileId(profileId: String): Int
}
