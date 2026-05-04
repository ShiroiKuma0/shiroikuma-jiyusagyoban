package com.opentasker.automation.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.opentasker.automation.data.dao.AutomationRuleDao
import com.opentasker.automation.data.dao.ExecutionLogDao
import com.opentasker.automation.data.entity.AutomationRuleEntity
import com.opentasker.automation.data.entity.ExecutionLogEntity

/**
 * Room database for automation rules and execution logs.
 * Extends the existing OpenTasker database with automation tables.
 */
@Database(
    entities = [
        AutomationRuleEntity::class,
        ExecutionLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(AutomationConverters::class)
abstract class AutomationDatabase : RoomDatabase() {
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun executionLogDao(): ExecutionLogDao
}
