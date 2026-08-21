package com.opentasker.core.storage

import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.RoomDatabase
import java.util.concurrent.CountDownLatch

const val OPEN_TASKER_DATABASE_SCHEMA_VERSION = 15

@Database(
    entities = [ProjectEntity::class, ProfileEntity::class, TaskEntity::class, SceneEntity::class, VariableEntity::class, RunLogEntity::class, EditHistoryEntity::class, ExecutionJournalEntity::class],
    version = OPEN_TASKER_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun profileDao(): ProfileDao
    abstract fun taskDao(): TaskDao
    abstract fun sceneDao(): SceneDao
    abstract fun variableDao(): VariableDao
    abstract fun runLogDao(): RunLogDao
    abstract fun editHistoryDao(): EditHistoryDao
    abstract fun executionJournalDao(): ExecutionJournalDao
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
