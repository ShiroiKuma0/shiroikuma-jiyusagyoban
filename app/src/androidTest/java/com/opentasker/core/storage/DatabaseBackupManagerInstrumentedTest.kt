package com.opentasker.core.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import java.io.File
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

            val failure = manager.inspectEncryptedRestore(Uri.fromFile(encrypted), "correct".toCharArray()).exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertArrayEquals(pendingBefore, pending.readBytes())
            val candidate = DatabaseBackupManager.candidateRestoreFile(context, TEST_DATABASE)
            assertFalse(context.filesDir.resolve("backups/${candidate.name}.decrypt.tmp").exists())
            assertFalse("a failed decrypt leaves nothing to stage", candidate.exists())
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
        val appKey = KeptDatabaseKey(context)
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

            // The path the restore picker takes for a snapshot: decrypt into the review, then Stage.
            val restoring = DatabaseBackupManager(context, db, ENCRYPTED_TEST_DATABASE)
            assertTrue(restoring.isEncryptedBackup(Uri.fromFile(exported)))
            val reviewed = restoring.inspectEncryptedRestore(Uri.fromFile(exported), passphrase.toCharArray()).getOrThrow()
            assertEquals("the review counts what the snapshot holds", 1, reviewed.profileCount)
            restoring.stageInspectedRestore().getOrThrow()
            val applied = DatabaseBackupManager.applyPendingRestoreIfPresent(context, ENCRYPTED_TEST_DATABASE)
            assertTrue("restore must apply on the new install", applied is PendingRestoreApplyResult.Applied)

            key = DatabaseSecurity.prepareEncryptedDatabase(context, ENCRYPTED_TEST_DATABASE)
            db = encryptedDatabase(context, key)
            assertEquals(listOf("Survives reinstall"), db.profileDao().getAll().map { it.name })
        } finally {
            // First, so a cleanup step that throws can't leave the app without its key.
            try {
                appKey.putBack()
            } finally {
                db.close()
                exported.delete()
                cleanupEncrypted(context)
            }
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
            // Counted from the directory itself, not from listBackups(), which retention and the
            // backup card read: the rollback has to be in that listing, at its full size. Only this
            // database's copies: on a phone the app's own backups share the folder.
            val copyName = Regex("""_(backup|pre_restore|restore_failed)_[^/]*\.db$""")
            val onDisk = rollback.parentFile!!.listFiles { file ->
                file.name.startsWith("${TEST_DATABASE.removeSuffix(".db")}_") && copyName.containsMatchIn(file.name)
            }.orEmpty()
            val listed = manager.listBackups()
            assertEquals(onDisk.size, listed.size)
            assertEquals(onDisk.sumOf { it.length() }, listed.sumOf { it.length() })
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

            // The pass the snapshot worker runs; the fresh backup is the newest, so it stays.
            assertEquals(2, manager.pruneSnapshots(ConfigurationSnapshotPolicy(maxAgeDays = 3)))
            assertTrue(copies.none { it.exists() })
            assertTrue("the fresh backup stays", source.exists())
        } finally {
            db.close()
            cleanup(context)
        }
    }

    /**
     * When the write that records a restore's Undo fails and the old database can't be copied back
     * either, the restored database stays live, so its Undo record is written again. Otherwise a
     * restart reads the record from before and never offers or cleans up this rollback (A-438).
     */
    @Test
    fun aRestoreThatKeepsItsDatabaseAfterTheUndoRecordFailsWritesTheRecordAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        try {
            val prefs = FlakyRestorePrefs(failingCommits = 1)
            val (result, rollbackName) = restoreWithTheUndoRecordAndTheCopyBackFailing(context, prefs) { true }

            assertTrue("the restore reports it failed: $result", result is PendingRestoreApplyResult.Failed)
            assertTrue("the restored database stays live", (result as PendingRestoreApplyResult.Failed).replacementKept)
            assertEquals(rollbackName, prefs.disk["rollback_name:$TEST_DATABASE"])
            val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
            try {
                assertEquals(listOf("In the backup"), db.profileDao().getAll().map { it.name })
            } finally {
                db.close()
            }
        } finally {
            cleanup(context)
        }
    }

    /**
     * A failed restore that keeps its database and can't drop what belonged to the one it replaced
     * leaves a spent journal for the next start to finish, and its failed copy is a copy (40290d94).
     */
    @Test
    fun aKeptRestoreWhoseStateDropFailsLeavesItsJournalForTheNextStart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        try {
            val (result, _) = restoreWithTheUndoRecordAndTheCopyBackFailing(context, FlakyRestorePrefs(failingCommits = 1)) { false }

            assertTrue("the restore reports it failed: $result", result is PendingRestoreApplyResult.Failed)
            val failed = result as PendingRestoreApplyResult.Failed
            assertTrue("the restored database stays live", failed.replacementKept)
            val pending = DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE)
            val spent = File(pending.parentFile, "${pending.name}.spent")
            assertFalse(pending.exists())
            assertTrue("the journal waits for the next start", spent.isFile)
            val failedCopy = requireNotNull(failed.failedBackup)
            assertTrue("the failed copy is a copy beside the journal", failedCopy.isFile && failedCopy != spent)

            var drops = 0
            assertEquals(
                PendingRestoreApplyResult.StoppedAfterSwap,
                DatabaseBackupManager.applyPendingRestoreIfPresent(context, TEST_DATABASE) { drops++; true },
            )
            assertEquals(1, drops)
            assertFalse("the next start finishes the journal", spent.exists())
        } finally {
            cleanup(context)
        }
    }

    /** A restore record stored with another type fails the restore, and its journal goes instead of throwing at every start (A-438). */
    @Test
    fun aRestoreRecordOfAnotherTypeFailsTheRestoreAndItsJournalGoes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
            val backup = try {
                db.profileDao().insert(Profile(name = "In the backup", enterTaskId = 1).toEntity())
                DatabaseBackupManager(context, db, TEST_DATABASE).backup().getOrThrow()
            } finally {
                db.close()
            }
            backup.copyTo(DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE), overwrite = true)
            val prefs = FlakyRestorePrefs().also {
                it.memory["rollback_name:$TEST_DATABASE"] = 5L
                it.disk.putAll(it.memory)
            }
            val restoring = withRestorePrefs(context, prefs)

            val result = DatabaseBackupManager.applyPendingRestoreIfPresent(restoring, TEST_DATABASE)

            assertTrue("the restore reports it failed: $result", result is PendingRestoreApplyResult.Failed)
            assertFalse((result as PendingRestoreApplyResult.Failed).replacementKept)
            assertEquals(PendingRestoreApplyResult.NoPending, DatabaseBackupManager.applyPendingRestoreIfPresent(restoring, TEST_DATABASE))
            // The bad record goes with it, so it can't fail every restore after this one.
            assertFalse(prefs.memory.containsKey("rollback_name:$TEST_DATABASE"))
            assertFalse(prefs.disk.containsKey("rollback_name:$TEST_DATABASE"))
            backup.copyTo(DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE), overwrite = true)
            val next = DatabaseBackupManager.applyPendingRestoreIfPresent(restoring, TEST_DATABASE)
            assertTrue("the next restore applies: $next", next is PendingRestoreApplyResult.Applied)
        } finally {
            cleanup(context)
        }
    }

    /** Setup's look at Undo reads a record of another type as none and clears it, instead of throwing. */
    @Test
    fun undoReadsARecordOfAnotherTypeAsNoneAndClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        val prefs = FlakyRestorePrefs().also {
            it.memory["rollback_name:$TEST_DATABASE"] = 5L
            it.memory["restored_at_ms:$TEST_DATABASE"] = "yesterday"
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        try {
            assertEquals(null, DatabaseBackupManager(withRestorePrefs(context, prefs), db, TEST_DATABASE).lastRestoreRollback())
            assertFalse(prefs.memory.containsKey("rollback_name:$TEST_DATABASE"))
            assertFalse(prefs.memory.containsKey("restored_at_ms:$TEST_DATABASE"))
        } finally {
            db.close()
            cleanup(context)
        }
    }

    /**
     * A restore whose journal can't be retired, and whose old database can't be copied back
     * either, keeps the restored database live, so Undo has to point at the copy it replaced even
     * though no write of that record was tried before the failure (verifier 24). A write that
     * fails once is tried again there too (verifier 25).
     */
    @Test
    fun aKeptRestoreWhoseJournalWontRetireStillRecordsItsUndo() = keptRestoreWithBlockedJournal(failingCommits = 0)

    @Test
    fun aKeptRestoreWhoseJournalWontRetireRecordsItsUndoAfterAFailedWrite() = keptRestoreWithBlockedJournal(failingCommits = 1)

    private fun keptRestoreWithBlockedJournal(failingCommits: Int) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        val pending = DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE)
        // A non-empty directory on each name makes the journal's rename and the copy back fail.
        val spentBlock = File(pending.parentFile, "${pending.name}.spent")
        val copyBackBlock = File(context.getDatabasePath(TEST_DATABASE).parentFile, "$TEST_DATABASE.rollback.tmp")
        try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
            try {
                db.profileDao().insert(Profile(name = "In the backup", enterTaskId = 1).toEntity())
                val backup = DatabaseBackupManager(context, db, TEST_DATABASE).backup().getOrThrow()
                db.profileDao().insert(Profile(name = "Replaced", enterTaskId = 1).toEntity())
                backup.copyTo(pending, overwrite = true)
            } finally {
                db.close()
            }
            listOf(spentBlock, copyBackBlock).forEach { block ->
                assertTrue(block.mkdirs())
                File(block, "keep").writeText("x")
            }
            val prefs = FlakyRestorePrefs(failingCommits).also {
                it.memory["rollback_name:$TEST_DATABASE"] = "opentasker-backup-test_pre_restore_older.db"
                it.disk.putAll(it.memory)
            }
            // The start of the apply would finish a spent journal; this one is a directory to keep.
            var drops = 0

            val result = DatabaseBackupManager.applyPendingRestoreIfPresent(withRestorePrefs(context, prefs), TEST_DATABASE) { drops++ > 0 }

            assertTrue("the restore reports it failed: $result", result is PendingRestoreApplyResult.Failed)
            assertTrue("the restored database stays live", (result as PendingRestoreApplyResult.Failed).replacementKept)
            val replaced = context.filesDir.resolve("backups")
                .listFiles { file -> file.name.startsWith("opentasker-backup-test_pre_restore_") && file.name.endsWith(".db") }
                .orEmpty()
                .single()
            assertEquals(replaced.name, prefs.disk["rollback_name:$TEST_DATABASE"])
        } finally {
            spentBlock.deleteRecursively()
            copyBackBlock.deleteRecursively()
            cleanup(context)
        }
    }

    /**
     * With no database before it, a restore has nothing for Undo, so it clears the record of an
     * earlier restore. A clear that fails once is tried again, or Setup would offer to go back to
     * that older copy over the new database (verifier 24).
     */
    @Test
    fun aRestoreOverNoDatabaseClearsAnOlderUndoEvenAfterAFailedWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanup(context)
        try {
            val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
            try {
                db.profileDao().insert(Profile(name = "In the backup", enterTaskId = 1).toEntity())
                DatabaseBackupManager(context, db, TEST_DATABASE).backup().getOrThrow()
                    .copyTo(DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE), overwrite = true)
            } finally {
                db.close()
            }
            assertTrue(context.deleteDatabase(TEST_DATABASE))
            val prefs = FlakyRestorePrefs(failingCommits = 1).also {
                it.memory["rollback_name:$TEST_DATABASE"] = "opentasker-backup-test_pre_restore_older.db"
                it.disk.putAll(it.memory)
            }

            val result = DatabaseBackupManager.applyPendingRestoreIfPresent(withRestorePrefs(context, prefs), TEST_DATABASE)

            assertTrue("the restore applies: $result", result is PendingRestoreApplyResult.Applied)
            assertFalse("the older Undo is gone from disk", prefs.disk.containsKey("rollback_name:$TEST_DATABASE"))
        } finally {
            cleanup(context)
        }
    }

    /**
     * Stages a backup over a database holding a profile the backup doesn't have, then applies it
     * with [prefs] standing in for the restore record. A failed commit moves the copy of the
     * replaced database aside, so copying it back fails too; it's put back after. Returns the
     * result and the name of that copy.
     */
    private suspend fun restoreWithTheUndoRecordAndTheCopyBackFailing(
        context: Context,
        prefs: FlakyRestorePrefs,
        dropStateOfReplacedDatabase: () -> Boolean,
    ): Pair<PendingRestoreApplyResult, String> {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE).build()
        try {
            db.profileDao().insert(Profile(name = "In the backup", enterTaskId = 1).toEntity())
            val backup = DatabaseBackupManager(context, db, TEST_DATABASE).backup().getOrThrow()
            db.profileDao().insert(Profile(name = "Replaced", enterTaskId = 1).toEntity())
            backup.copyTo(DatabaseBackupManager.pendingRestoreFile(context, TEST_DATABASE), overwrite = true)
        } finally {
            db.close()
        }
        val held = mutableListOf<Pair<File, File>>()
        prefs.onFailedCommit = {
            context.filesDir.resolve("backups")
                .listFiles { file -> file.name.startsWith("opentasker-backup-test_pre_restore_") && file.name.endsWith(".db") }
                .orEmpty()
                .forEach { copy ->
                    val aside = File(copy.parentFile, "${copy.name}.held")
                    assertTrue(copy.renameTo(aside))
                    held += copy to aside
                }
        }
        val result = DatabaseBackupManager.applyPendingRestoreIfPresent(
            withRestorePrefs(context, prefs),
            TEST_DATABASE,
            dropStateOfReplacedDatabase,
        )
        held.forEach { (copy, aside) -> aside.renameTo(copy) }
        return result to held.single().first.name
    }

    private fun withRestorePrefs(context: Context, prefs: SharedPreferences): Context =
        object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                if (name == "database_restore") prefs else super.getSharedPreferences(name, mode)
        }

    /**
     * Restore prefs in memory, with what a restart would read kept apart in [disk]. As on Android,
     * a commit that fails still changes what this process reads.
     */
    private class FlakyRestorePrefs(private var failingCommits: Int = 0) : SharedPreferences {
        val memory = mutableMapOf<String, Any?>()
        val disk = mutableMapOf<String, Any?>()
        var onFailedCommit: () -> Unit = {}

        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = if (key in memory) memory[key] as String? else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (key in memory) memory[key] as MutableSet<String>? else defValues
        override fun getInt(key: String?, defValue: Int): Int = (memory[key] ?: defValue) as Int
        override fun getLong(key: String?, defValue: Long): Long = (memory[key] ?: defValue) as Long
        override fun getFloat(key: String?, defValue: Float): Float = (memory[key] ?: defValue) as Float
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (memory[key] ?: defValue) as Boolean
        override fun contains(key: String?): Boolean = key in memory
        override fun edit(): SharedPreferences.Editor = FlakyEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FlakyEditor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearFirst = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toMutableSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removals += requireNotNull(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFirst = true
                return this
            }

            override fun commit(): Boolean {
                if (clearFirst) memory.clear()
                removals.forEach { memory.remove(it) }
                memory.putAll(changes)
                if (failingCommits > 0) {
                    failingCommits--
                    onFailedCommit()
                    return false
                }
                disk.clear()
                disk.putAll(memory)
                return true
            }

            override fun apply() {
                commit()
            }
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
        // The database key isn't cleared here: it's the app's own key too. The one test that
        // clears it puts it back through [KeptDatabaseKey].
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
