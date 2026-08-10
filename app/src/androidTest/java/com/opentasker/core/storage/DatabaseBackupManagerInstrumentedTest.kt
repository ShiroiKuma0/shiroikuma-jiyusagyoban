package com.opentasker.core.storage

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseBackupManagerInstrumentedTest {
    @Test
    fun backupSucceedsForPopulatedCurrentSchemaDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            val taskId = db.taskDao().insert(Task(name = "Report task").toEntity())
            db.profileDao().insert(Profile(name = "Report profile", enterTaskId = taskId).toEntity())
            db.runLogDao().insert(
                RunLogEntry(
                    taskId = taskId,
                    taskName = "Report task",
                    durationMs = 12,
                    success = true,
                    message = "Completed",
                ).toEntity(),
            )

            val backup = manager.backup().getOrThrow()

            assertTrue(backup.exists())
            assertTrue(backup.length() > 0L)
        } finally {
            db.close()
            cleanup(context)
        }
    }

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

    @Test
    fun restoreRejectsIncompatibleSchemaShape() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            db.profileDao().insert(Profile(name = "Current schema profile", enterTaskId = 1).toEntity())
            val backup = manager.backup().getOrThrow()
            SQLiteDatabase.openDatabase(backup.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.execSQL("ALTER TABLE run_logs RENAME TO run_logs_old")
                sqlite.execSQL(
                    """
                    CREATE TABLE run_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        taskName TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        message TEXT NOT NULL,
                        source TEXT
                    )
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    INSERT INTO run_logs (id, taskId, taskName, timestamp, durationMs, success, message, source)
                    SELECT id, taskId, taskName, timestamp, durationMs, success, message, source FROM run_logs_old
                    """.trimIndent(),
                )
                sqlite.execSQL("DROP TABLE run_logs_old")
            }

            val failure = manager.restore(backup).exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(failure?.message?.contains("schema version") == true)
        } finally {
            db.close()
            cleanup(context)
        }
    }

    @Test
    fun corruptedEncryptedRestorePreservesExistingJournalAndCleansPlaintextStaging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        val encrypted = context.cacheDir.resolve("opentasker-corrupt-restore.otbackup")
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            db.profileDao().insert(Profile(name = "Keep pending", enterTaskId = 1).toEntity())
            val backup = manager.backup().getOrThrow()
            manager.restore(backup).getOrThrow()
            val pending = DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE)
            val pendingBefore = pending.readBytes()

            backup.inputStream().use { input ->
                encrypted.outputStream().use { output ->
                    BackupEncryption.encrypt(input, output, "correct".toCharArray())
                }
            }
            val corrupted = encrypted.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            encrypted.writeBytes(corrupted)

            val failure = manager.stageEncryptedRestore(Uri.fromFile(encrypted), "correct".toCharArray()).exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertArrayEquals(pendingBefore, pending.readBytes())
            assertFalse(context.filesDir.resolve("backups/${pending.name}.decrypt.tmp").exists())
        } finally {
            db.close()
            encrypted.delete()
            cleanup(context)
        }
    }

    /**
     * The reinstall/device-transfer case the feature exists for: the SQLCipher key that encrypted
     * the database is gone, and the exported `.otbackup` must still open.
     */
    @Test
    fun encryptedExportRestoresAfterTheDatabaseKeyIsLost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanupEncrypted(context)

        val exported = context.cacheDir.resolve("opentasker-portable-export.otbackup")
        val passphrase = "portable-passphrase"
        var key = DatabaseSecurity.prepareEncryptedDatabase(context, ENCRYPTED_TEST_DATABASE)
        var db = encryptedDatabase(context, key)
        try {
            db.profileDao().insert(Profile(name = "Survives reinstall", enterTaskId = 1).toEntity())
            val manager = DatabaseBackupManager(context, db, ENCRYPTED_TEST_DATABASE)
            val backup = manager.backup().getOrThrow()
            assertFalse(
                "the managed backup must still be ciphertext",
                DatabaseSecurity.isPlaintext(backup),
            )

            manager.exportEncryptedBackup(backup, Uri.fromFile(exported), passphrase.toCharArray()).getOrThrow()
            assertFalse(
                "the plaintext staging copy must not survive the export",
                context.filesDir.resolve("backups/${backup.name}.portable.tmp").exists(),
            )
            db.close()

            // Simulate a fresh install: the wrapped SQLCipher key is destroyed with app data, so
            // the next getOrCreate() mints a different one and the old ciphertext is unreadable.
            context.deleteDatabase(ENCRYPTED_TEST_DATABASE)
            context.getSharedPreferences("database_security", 0).edit().clear().commit()

            DatabaseBackupManager(context, db, ENCRYPTED_TEST_DATABASE)
                .stageEncryptedRestore(Uri.fromFile(exported), passphrase.toCharArray())
                .getOrThrow()
            val applied = DatabaseBackupManager.applyPendingRestoreIfPresent(context, ENCRYPTED_TEST_DATABASE)
            assertTrue("restore must apply on the new install", applied is PendingRestoreApplyResult.Applied)

            key = DatabaseSecurity.prepareEncryptedDatabase(context, ENCRYPTED_TEST_DATABASE)
            db = encryptedDatabase(context, key)
            assertEquals(listOf("Survives reinstall"), db.profileDao().getAll().map { it.name })
        } finally {
            db.close()
            exported.delete()
            cleanupEncrypted(context)
        }
    }

    private fun encryptedDatabase(context: android.content.Context, key: ByteArray): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, ENCRYPTED_TEST_DATABASE)
            .addMigrations(*DatabaseMigrations.getManualMigrations())
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .allowMainThreadQueries()
            .build()

    private fun cleanupEncrypted(context: android.content.Context) {
        context.deleteDatabase(ENCRYPTED_TEST_DATABASE)
        DatabaseBackupManager.pendingRestoreFile(context, ENCRYPTED_TEST_DATABASE).delete()
        context.filesDir.resolve("backups")
            .listFiles { file -> file.name.startsWith(ENCRYPTED_TEST_DATABASE.removeSuffix(".db")) }
            ?.forEach { it.delete() }
        context.getSharedPreferences("database_security", 0).edit().clear().commit()
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
        const val ENCRYPTED_TEST_DATABASE = "opentasker-portable-backup-test.db"
    }
}
