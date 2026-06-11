package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseBackupManagerInstrumentedTest {
    @Test
    fun stagedRestoreAppliesBeforeDatabaseReopens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        var db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            db.profileDao().insert(Profile(name = "Restored profile", enterTaskId = 1).toEntity())
            val backup = manager.backup().getOrThrow()
            db.profileDao().insert(Profile(name = "Scratch profile", enterTaskId = 2).toEntity())
            db.close()

            manager.restore(backup).getOrThrow()
            val result = DatabaseBackupManager.applyPendingRestoreIfPresent(context, TEST_DATABASE)

            assertTrue(result is PendingRestoreApplyResult.Applied)
            db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
                .allowMainThreadQueries()
                .build()
            assertEquals(listOf("Restored profile"), db.profileDao().getAll().map { it.name })
        } finally {
            db.close()
            cleanup(context)
        }
    }

    @Test
    fun invalidPendingRestoreLeavesExistingDatabaseIntact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        var db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            db.profileDao().insert(Profile(name = "Keep me", enterTaskId = 1).toEntity())
            db.close()
            DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE).writeText("not a sqlite database")

            val result = DatabaseBackupManager.applyPendingRestoreIfPresent(context, TEST_DATABASE)

            assertTrue(result is PendingRestoreApplyResult.Failed)
            db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
                .allowMainThreadQueries()
                .build()
            assertEquals(listOf("Keep me"), db.profileDao().getAll().map { it.name })
        } finally {
            db.close()
            cleanup(context)
        }
    }

    private fun cleanup(context: android.content.Context) {
        context.deleteDatabase(TEST_DATABASE)
        DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE).delete()
        context.filesDir.resolve("backups")
            .listFiles { file -> file.name.startsWith(TEST_DATABASE.removeSuffix(".db")) }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TEST_DATABASE = "opentasker-backup-test.db"
    }
}
