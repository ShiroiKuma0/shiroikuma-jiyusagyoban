package com.opentasker.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import java.util.concurrent.CountDownLatch

// Fork numbering: upstream v6 (variables.isSecret), v7 (profiles.requiresRiskAcknowledgement),
// v8 (run_logs/edit_history indexes) and v10 (edit_history undo/redo) are renumbered 18/19/20/22
// here because the fork chain already occupies 5..17 and 21. Upstream's v9 (projects) is absent on
// purpose: the fork introduced projects in its own chain long before upstream did. Upstream's
// v11..v15 (held run-log rows, profile priority/lifetime, admission limits, fallback task, the
// execution journal) are likewise renumbered 23..27.
const val OPEN_TASKER_DATABASE_SCHEMA_VERSION = 27

@Database(
    entities = [ProfileEntity::class, TaskEntity::class, SceneEntity::class, VariableEntity::class, RunLogEntity::class, EditHistoryEntity::class, ExecutionJournalEntity::class, ProjectEntity::class, ItemMetaEntity::class, ItemGroupEntity::class, BandSampleEntity::class, BandDailyEntity::class, BandSleepEntity::class, BandSyncEntity::class],
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
    abstract fun executionJournalDao(): ExecutionJournalDao
    abstract fun projectDao(): ProjectDao
    abstract fun itemMetaDao(): ItemMetaDao
    abstract fun itemGroupDao(): ItemGroupDao
    abstract fun bandSampleDao(): BandSampleDao
    abstract fun bandDailyDao(): BandDailyDao
    abstract fun bandSleepDao(): BandSleepDao
    abstract fun bandSyncDao(): BandSyncDao
}

/** Process-local handoff from the application bootstrap to workers in the storage module. */
object AppDatabaseProvider {
    private const val READY_TIMEOUT_SECONDS = 30L

    @Volatile
    private var database: AppDatabase? = null
    private val ready = CountDownLatch(1)

    fun publish(value: AppDatabase) {
        database = value
        ready.countDown()
    }

    fun await(): AppDatabase {
        database?.let { return it }
        check(ready.await(READY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            "Database was not initialized."
        }
        return checkNotNull(database) { "Database was not initialized." }
    }
}
