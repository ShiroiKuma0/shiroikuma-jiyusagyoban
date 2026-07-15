package com.opentasker.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase

// Fork numbering: upstream v6 (variables.isSecret) and v7 (profiles.requiresRiskAcknowledgement)
// are renumbered 18/19 here because the fork chain already occupies 5..17.
const val OPEN_TASKER_DATABASE_SCHEMA_VERSION = 19

@Database(
    entities = [ProfileEntity::class, TaskEntity::class, SceneEntity::class, VariableEntity::class, RunLogEntity::class, EditHistoryEntity::class, ProjectEntity::class, ItemMetaEntity::class, ItemGroupEntity::class],
    version = OPEN_TASKER_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun taskDao(): TaskDao
    abstract fun sceneDao(): SceneDao
    abstract fun variableDao(): VariableDao
    abstract fun runLogDao(): RunLogDao
    abstract fun editHistoryDao(): EditHistoryDao
    abstract fun projectDao(): ProjectDao
    abstract fun itemMetaDao(): ItemMetaDao
    abstract fun itemGroupDao(): ItemGroupDao
}
