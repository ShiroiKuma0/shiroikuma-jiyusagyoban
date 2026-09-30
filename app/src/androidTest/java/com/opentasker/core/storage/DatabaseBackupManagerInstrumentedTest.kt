package com.opentasker.core.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Validation opens each copy, so SQLite writes `-wal`/`-shm` beside it and the staging file's
     * sidecars are orphaned by the rename. Nothing removed them, so every backup left four stray
     * files that retention never counted and never pruned.
     */
    @Test
    fun backupsLeaveNoOrphanedSidecarsAndPruningRemovesTheirs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            db.profileDao().insert(Profile(name = "Snapshot me", enterTaskId = 1).toEntity())

            val backup = manager.backup().getOrThrow()

            val strays = backupFiles(context).filterNot { it.name.endsWith(".db") }
            assertTrue("unexpected sidecars left behind: ${strays.map { it.name }}", strays.isEmpty())
            assertTrue(backup.exists())

            // Retention must take the whole set with it, not just the .db file.
            val pruned = manager.pruneSnapshots(
                ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 2, maxAgeDays = 1),
                nowMs = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000,
            )
            assertEquals(0, pruned)
            assertTrue("the newest snapshot is never pruned", backup.exists())
        } finally {
            db.close()
            cleanup(context)
        }
    }

    /**
     * Every task run inserts a run_logs row, and the copy used to run with no lock held, so an
     * auto-checkpoint could rewrite main-file pages halfway through it (A-324). The writer commits
     * a whole batch per transaction while backups run, so a copy that matches one moment holds
     * whole batches only, numbered with no gaps, and everything committed before backup() began.
     */
    @Test
    fun backupsTakenWhileRunsKeepLoggingAreConsistentSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        try {
            backUpWhileLogging(context, db, TEST_DATABASE).forEach { (committedBefore, backup) ->
                SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                    assertConsistentSnapshot(backup, committedBefore) { sql -> copy.rawQuery(sql, null) }
                }
            }
        } finally {
            db.close()
            cleanup(context)
        }
    }

    /** The same under SQLCipher, where folding a copied WAL has to open the copy with the key. */
    @Test
    fun encryptedBackupsTakenWhileRunsKeepLoggingAreConsistentSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanupEncrypted(context)

        val db = encryptedDatabase(context, DatabaseSecurity.prepareEncryptedDatabase(context, ENCRYPTED_TEST_DATABASE))
        try {
            backUpWhileLogging(context, db, ENCRYPTED_TEST_DATABASE).forEach { (committedBefore, backup) ->
                assertFalse("${backup.name} must still be ciphertext", DatabaseSecurity.isPlaintext(backup))
                DatabaseSecurity.openEncryptedReadOnly(backup, context).use { copy ->
                    assertConsistentSnapshot(backup, committedBefore) { sql -> copy.rawQuery(sql, null) }
                }
            }
        } finally {
            db.close()
            cleanupEncrypted(context)
        }
    }

    /**
     * Takes backups while a writer keeps committing. Each writer transaction adds batch n and drops
     * the oldest batch once [LOGGING_WINDOW] are stored, so the file stays a steady size while the
     * WAL keeps filling and checkpointing. Returns each backup with the batch count committed
     * before its backup() call began.
     */
    private suspend fun backUpWhileLogging(
        context: android.content.Context,
        db: AppDatabase,
        databaseName: String,
    ): List<Pair<Long, java.io.File>> = coroutineScope {
        val manager = DatabaseBackupManager(context, db, databaseName)
        val taskId = db.taskDao().insert(Task(name = "Busy task").toEntity())
        val padding = "x".repeat(LOGGING_ROW_PADDING)
        val committed = AtomicLong(0)
        val writing = AtomicBoolean(true)
        val writer = launch(Dispatchers.IO) {
            var batch = 0L
            while (writing.get()) {
                batch++
                db.withTransaction {
                    repeat(LOGGING_BATCH_ROWS) { row ->
                        db.runLogDao().insert(
                            RunLogEntry(
                                taskId = taskId,
                                taskName = LOGGING_TASK_NAME,
                                durationMs = batch,
                                success = true,
                                message = "$row $padding",
                            ).toEntity(),
                        )
                    }
                    if (batch > LOGGING_WINDOW) {
                        // Ids are AUTOINCREMENT and one writer adds a batch per transaction, so the
                        // oldest batch is exactly the lowest LOGGING_BATCH_ROWS ids.
                        db.openHelper.writableDatabase.execSQL(
                            "DELETE FROM run_logs WHERE id < (SELECT MIN(id) FROM run_logs) + $LOGGING_BATCH_ROWS",
                        )
                    }
                }
                committed.set(batch)
            }
        }
        try {
            while (committed.get() < LOGGING_WINDOW) delay(20)
            List(LOGGING_BACKUPS) {
                val committedBefore = committed.get()
                committedBefore to manager.backup().getOrThrow()
            }
        } finally {
            writing.set(false)
            writer.join()
        }
    }

    private fun assertConsistentSnapshot(backup: java.io.File, committedBefore: Long, query: (String) -> Cursor) {
        query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("${backup.name} integrity", "ok", cursor.getString(0))
        }
        val batches = query(
            "SELECT durationMs, COUNT(*) FROM run_logs WHERE taskName = '$LOGGING_TASK_NAME' " +
                "GROUP BY durationMs ORDER BY durationMs",
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getInt(1)) }
        }
        assertTrue("${backup.name} holds no batches", batches.isNotEmpty())
        val first = batches.first().first
        val last = batches.last().first
        assertEquals("${backup.name} skips a batch", (first..last).toList(), batches.map { it.first })
        assertTrue(
            "${backup.name} holds part of a batch: ${batches.filter { it.second != LOGGING_BATCH_ROWS }}",
            batches.all { it.second == LOGGING_BATCH_ROWS },
        )
        assertEquals("${backup.name} window", minOf(last, LOGGING_WINDOW.toLong()), batches.size.toLong())
        assertTrue("${backup.name} ends at batch $last, before $committedBefore", last >= committedBefore)
    }

    /**
     * A restore copies the database it replaces before Room opens, so the WAL can still hold the
     * previous run's last writes. The copy used to take the main file alone, then the WAL was
     * deleted, and neither the copy nor a failed restore's file was ever listed, counted or pruned
     * (A-323).
     */
    @Test
    fun restoreKeepsAValidRollbackWithTheOldWalAndListsIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        val dbFile = context.getDatabasePath(TEST_DATABASE)
        val crashed = java.io.File(dbFile.parentFile, "opentasker-backup-test-crashed.db")

        var db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        try {
            db.profileDao().insert(Profile(name = "In the backup", enterTaskId = 1).toEntity())
            val backup = DatabaseBackupManager(context, db, TEST_DATABASE).backup().getOrThrow()
            db.profileDao().insert(Profile(name = "Only in the WAL", enterTaskId = 1).toEntity())
            // A process that dies here leaves the last commit in the WAL. Copy that state aside
            // while it is quiet, close Room (which would checkpoint it away), then put it back.
            dbFile.copyTo(crashed, overwrite = true)
            val wal = java.io.File("${dbFile.path}-wal")
            assertTrue("the last commit must still be in the WAL for this test to mean anything", wal.length() > 0L)
            wal.copyTo(java.io.File("${crashed.path}-wal"), overwrite = true)
            db.close()
            context.deleteDatabase(TEST_DATABASE)
            crashed.renameTo(dbFile)
            java.io.File("${crashed.path}-wal").renameTo(wal)

            backup.copyTo(DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE), overwrite = true)
            val applied = DatabaseBackupManager.applyPendingRestoreIfPresent(context, TEST_DATABASE)
            assertTrue("restore must apply", applied is PendingRestoreApplyResult.Applied)
            val rollback = requireNotNull((applied as PendingRestoreApplyResult.Applied).previousBackup)

            assertFalse("the rollback must stand alone", java.io.File("${rollback.path}-wal").exists())
            SQLiteDatabase.openDatabase(rollback.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                val names = copy.rawQuery("SELECT name FROM profiles ORDER BY id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertEquals(listOf("In the backup", "Only in the WAL"), names)
            }

            db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            assertEquals(listOf("In the backup"), db.profileDao().getAll().map { it.name })
            assertTrue("the rollback validates", manager.inspectManagedBackup(rollback).isSuccess)
            assertTrue("the rollback is listed", rollback.name in manager.listBackups().map { it.name })
            assertEquals(rollback.name, manager.lastRestoreRollback()?.file?.name)
            // Counted from the directory itself, not from listBackups(), which is what the storage
            // line reads: the rollback has to be in the Setup count and its bytes in the total.
            val copyName = Regex("""_(backup|pre_restore|restore_failed)_[^/]*\.db$""")
            val onDisk = rollback.parentFile!!.listFiles { file -> copyName.containsMatchIn(file.name) }.orEmpty()
            val (count, bytes) = manager.snapshotStorage()
            assertEquals(onDisk.size, count)
            assertEquals(onDisk.sumOf { it.length() }, bytes)
            assertTrue("a restore copy is never the latest backup", manager.latestBackup()?.name != rollback.name)
        } finally {
            db.close()
            crashed.delete()
            java.io.File("${crashed.path}-wal").delete()
            context.getSharedPreferences("database_restore", 0).edit().clear().commit()
            cleanup(context)
        }
    }

    @Test
    fun retentionPrunesOldRestoreCopiesToo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        try {
            val manager = DatabaseBackupManager(context, db, TEST_DATABASE)
            val source = manager.backup().getOrThrow()
            val dir = source.parentFile!!
            val old = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000
            val copies = listOf("pre_restore", "restore_failed").map { kind ->
                java.io.File(dir, "opentasker-backup-test_${kind}_2026-01-01_00-00-00.db").also { copy ->
                    source.copyTo(copy, overwrite = true)
                    copy.setLastModified(old)
                }
            }
            assertTrue(manager.listBackups().map { it.name }.containsAll(copies.map { it.name }))

            assertEquals(2, manager.deleteOldBackups(olderThanDays = 3))
            assertTrue(copies.none { it.exists() })
            assertTrue("the fresh backup stays", source.exists())
        } finally {
            db.close()
            cleanup(context)
        }
    }

    private fun backupFiles(context: android.content.Context): List<java.io.File> =
        context.filesDir.resolve("backups")
            .listFiles { file -> file.name.startsWith(TEST_DATABASE.removeSuffix(".db")) }
            ?.toList()
            .orEmpty()

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
        const val LOGGING_TASK_NAME = "batch"
        const val LOGGING_BATCH_ROWS = 50
        const val LOGGING_ROW_PADDING = 1_000
        const val LOGGING_WINDOW = 200
        const val LOGGING_BACKUPS = 8
    }
}
