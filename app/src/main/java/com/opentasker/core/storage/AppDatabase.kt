package com.opentasker.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase

// Fork numbering: upstream v6 (variables.isSecret), v7 (profiles.requiresRiskAcknowledgement),
// v8 (run_logs/edit_history indexes) and v10 (edit_history undo/redo) are renumbered 18/19/20/22
// here because the fork chain already occupies 5..17 and 21. Upstream's v9 (projects) is absent on
// purpose: the fork introduced projects in its own chain long before upstream did.
const val OPEN_TASKER_DATABASE_SCHEMA_VERSION = 22

@Database(
    entities = [ProfileEntity::class, TaskEntity::class, SceneEntity::class, VariableEntity::class, RunLogEntity::class, EditHistoryEntity::class, ProjectEntity::class, ItemMetaEntity::class, ItemGroupEntity::class, BandSampleEntity::class, BandDailyEntity::class, BandSleepEntity::class, BandSyncEntity::class],
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
    abstract fun bandSampleDao(): BandSampleDao
    abstract fun bandDailyDao(): BandDailyDao
    abstract fun bandSleepDao(): BandSleepDao
    abstract fun bandSyncDao(): BandSyncDao
}
