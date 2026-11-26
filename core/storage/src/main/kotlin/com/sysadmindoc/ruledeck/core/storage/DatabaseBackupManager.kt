package com.sysadmindoc.ruledeck.core.storage

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import androidx.room.withTransaction
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.text.countOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Creates exportable SQLite backups and stages restores for the next app start.
 */
class DatabaseBackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val databaseName: String = DATABASE_NAME,
) {
    private val tag = "DatabaseBackupManager"
    private val backupDir = backupDir(context).apply { mkdirs() }

    suspend fun backup(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // A database opened from v5 can briefly contain rows flagged by the Room migration but
            // not yet rewritten by the asynchronous startup migration. Never copy that plaintext.
            VariableRepository(db.variableDao()).requireEncryptedSecretRows()
            val sourceFile = context.getDatabasePath(databaseName)
            if (!sourceFile.exists()) {
                throw IOException(
                    "Database file does not exist at ${sourceFile.absolutePath} for configured name '$databaseName'",
                )
            }

            checkpointWalBeforeCopy(sourceFile)
            val backupFile = File(backupDir, "${databaseName.removeSuffix(".db")}_backup_${timestamp()}.db")
            val tempFile = File(backupDir, "${backupFile.name}.tmp")
            try {
                copyConsistentSnapshot(sourceFile, tempFile)
                validateDatabaseFile(tempFile)
                publishValidatedBackup(context, tempFile, backupFile)
            } catch (error: Exception) {
                // Validation opens the staged copy, so SQLite leaves -wal/-shm beside it. The
                // success path deletes those in publishValidatedBackup; the failure path used to
                // delete only the .tmp itself, leaving a pair of orphans per failed backup that
                // listBackups() cannot see and no retention pass ever collects.
                tempFile.delete()
                deleteDatabaseSidecars(tempFile)
                throw error
            }
            AppLogger.info(tag, "Database backed up to ${backupFile.absolutePath}")
            backupFile
        }.onFailure { error ->
            AppLogger.error(tag, "Backup failed: ${error.message}", error)
        }
    }

    /**
     * Copies the live database into [destination] as one file that matches a single commit (A-324).
     *
     * The copy used to run with no lock, so a task run committing mid-copy could let SQLite's
     * auto-checkpoint rewrite main-file pages the copy had not reached yet. It now runs inside a
     * Room write transaction. Nothing can commit while that is held, and the only checkpoint that
     * can still write the main file is a passive one finishing a commit from just before the lock,
     * whose pages the WAL holds too. So a WAL with frames in it is copied as well and folded into
     * the copy once the lock is released, which leaves the pair right even if a page changed under
     * the main-file copy. Retrying until the WAL was empty instead would starve behind a busy writer.
     */
    private suspend fun copyConsistentSnapshot(sourceFile: File, destination: File) {
        val sourceWal = File("${sourceFile.path}-wal")
        deleteDatabaseSidecars(destination)
        val walCopied = db.withTransaction {
            sourceFile.copyTo(destination, overwrite = true)
            val walHasFrames = sourceWal.length() > 0L
            if (walHasFrames) sourceWal.copyTo(File("${destination.path}-wal"), overwrite = true)
            walHasFrames
        }
        if (walCopied) DatabaseSecurity.foldWalIntoCopy(destination, context)
    }

    /**
     * Best effort. An emptied WAL makes the copy smaller, but the copy no longer depends on it:
     * [copyConsistentSnapshot] copies whatever WAL is left and folds it in. So a busy writer that
     * keeps this from finishing costs size, not the backup. It used to fail the backup after four
     * tries, which is exactly the steady-write case A-324 was about.
     */
    private suspend fun checkpointWalBeforeCopy(sourceFile: File) {
        var lastStatus: WalCheckpointStatus? = null
        repeat(WAL_CHECKPOINT_ATTEMPTS) { attempt ->
            val status = db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                readWalCheckpointStatus(cursor, databaseName)
            }
            lastStatus = status
            if (status.readyForMainFileCopy) return
            if (attempt < WAL_CHECKPOINT_ATTEMPTS - 1) {
                delay(WAL_CHECKPOINT_RETRY_DELAY_MS)
            }
        }

        AppLogger.info(
            tag,
            walCheckpointIncompleteMessage(
                databaseName = databaseName,
                sourcePath = sourceFile.absolutePath,
                status = requireNotNull(lastStatus),
            ),
        )
    }

    suspend fun exportEncryptedBackup(backupFile: File, destination: Uri, passphrase: CharArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val managedBackup = requireManagedBackupFile(backupFile)
                val operationContext = currentCoroutineContext()
                withPortableCopy(managedBackup) { portable ->
                    val output = context.contentResolver.openOutputStream(destination)
                        ?: throw IOException("Could not open export destination")
                    portable.inputStream().use { input ->
                        output.use { stream ->
                            BackupEncryption.encrypt(
                                input,
                                stream,
                                passphrase,
                                cancellationCheck = { operationContext.ensureActive() },
                            )
                        }
                    }
                }
                AppLogger.info(tag, "Encrypted backup exported to $destination")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AppLogger.error(tag, "Encrypted export failed: ${error.message}", error)
            }
        }

    /** Whether [uri] starts with the encrypted `.otbackup` header. Unreadable reads as no. */
    suspend fun isEncryptedBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BackupEncryption::hasEncryptedHeader) ?: false
        }.getOrDefault(false)
    }

    /**
     * Decrypts an encrypted `.otbackup` into the same candidate [inspectRestore] parks, so a
     * snapshot gets the same review and Stage steps as a plain database (A-439). Nothing is
     * staged here, and a failure leaves no candidate behind.
     */
    suspend fun inspectEncryptedRestore(
        uri: Uri,
        passphrase: CharArray,
        sourceLabel: String? = null,
    ): Result<RestoreCandidate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val operationContext = currentCoroutineContext()
                val candidate = candidateRestoreFile(context, databaseName)
                val temp = File(backupDir, "${candidate.name}.decrypt.tmp")
                shredFile(temp)
                if (temp.exists()) {
                    throw IOException("Could not clear interrupted encrypted-restore staging file")
                }
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open encrypted backup file")
                try {
                    input.use { encrypted ->
                        FileOutputStream(temp).use { plainOut ->
                            BackupEncryption.decrypt(
                                encrypted,
                                plainOut,
                                passphrase,
                                cancellationCheck = { operationContext.ensureActive() },
                            )
                            plainOut.fd.sync()
                        }
                    }
                    validateDatabaseFile(temp)
                    replaceFileAtomically(temp, candidate, "encrypted restore candidate")
                } catch (error: Throwable) {
                    shredFile(temp)
                    deleteDatabaseSidecars(temp)
                    throw error
                }
                summarize(candidate, sourceLabel ?: uri.lastPathSegment ?: candidate.name)
            }.onFailure { error ->
                shredFile(candidateRestoreFile(context, databaseName))
                if (error is CancellationException) throw error
                AppLogger.error(tag, "Encrypted restore inspection failed: ${error.message}", error)
            }
        }

    suspend fun exportBackup(backupFile: File, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val managedBackup = requireManagedBackupFile(backupFile)
            withPortableCopy(managedBackup) { portable ->
                val output = context.contentResolver.openOutputStream(destination)
                    ?: throw IOException("Could not open export destination")
                portable.inputStream().use { input ->
                    output.use { stream ->
                        input.copyTo(stream)
                    }
                }
            }
            AppLogger.info(tag, "Database backup exported to $destination")
        }.onFailure { error ->
            AppLogger.error(tag, "Backup export failed: ${error.message}", error)
        }
    }

    /**
     * Validates a candidate database and reports what it contains, **without** touching the
     * pending-restore journal.
     *
     * Selecting a file used to replace the journal immediately, so a user could not inspect the
     * candidate, could not tell it apart from a restore staged earlier, and could not back out.
     * The validated bytes are parked in a separate candidate file that [stageInspectedRestore]
     * promotes and [discardInspectedRestore] removes.
     */
    suspend fun inspectRestore(uri: Uri, sourceLabel: String? = null): Result<RestoreCandidate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open backup file")
                val candidate = candidateRestoreFile(context, databaseName)
                writeValidatedCopy(input, candidate, "restore candidate")
                summarize(candidate, sourceLabel ?: uri.lastPathSegment ?: candidate.name)
            }.onFailure { error ->
                shredFile(candidateRestoreFile(context, databaseName))
                AppLogger.error(tag, "Restore inspection failed: ${error.message}", error)
            }
        }

    /** Inspects a managed backup already in the app's backup directory. */
    suspend fun inspectManagedBackup(backupFile: File): Result<RestoreCandidate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val managedBackup = requireManagedBackupFile(backupFile)
                val candidate = candidateRestoreFile(context, databaseName)
                writeValidatedCopy(managedBackup.inputStream(), candidate, "restore candidate")
                summarize(candidate, managedBackup.name)
            }.onFailure { error ->
                shredFile(candidateRestoreFile(context, databaseName))
                AppLogger.error(tag, "Restore inspection failed: ${error.message}", error)
            }
        }

    /** Promotes the inspected candidate to the pending-restore journal applied at next start. */
    suspend fun stageInspectedRestore(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val candidate = candidateRestoreFile(context, databaseName)
            if (!candidate.exists()) throw IOException("No inspected restore candidate to stage")
            val pending = pendingRestoreFile(context, databaseName)
            replaceFileAtomically(candidate, pending, "pending restore")
            AppLogger.warn(tag, "Restore staged at ${pending.absolutePath}; restart RuleDeck to apply it")
            pending
        }.onFailure { error ->
            AppLogger.error(tag, "Restore staging failed: ${error.message}", error)
        }
    }

    /** Drops an inspected candidate the user decided not to stage, shredded like the journal. */
    fun discardInspectedRestore(): Boolean {
        val candidate = candidateRestoreFile(context, databaseName)
        if (!candidate.exists()) return false
        shredFile(candidate)
        return !candidate.exists()
    }

    /**
     * Cancels a staged restore. Only the validated pending journal is removed — backups, the live
     * database, and any pre-restore snapshot are untouched.
     */
    fun cancelPendingRestore(): Boolean {
        val pending = pendingRestoreFile(context, databaseName)
        if (!pending.exists()) return false
        shredFile(pending)
        return (!pending.exists()).also { deleted ->
            if (deleted) AppLogger.info(tag, "Pending restore cancelled")
            else AppLogger.warn(tag, "Could not cancel the pending restore journal")
        }
    }

    /** What the currently staged restore would install, or null when nothing is staged. */
    suspend fun pendingRestoreSummary(): RestoreCandidate? = withContext(Dispatchers.IO) {
        val pending = pendingRestoreFile(context, databaseName)
        if (!pending.exists()) return@withContext null
        runCatching { summarize(pending, pending.name) }.getOrElse { error ->
            RestoreCandidate(
                sourceLabel = pending.name,
                sizeBytes = pending.length(),
                schemaVersion = 0,
                error = error.message ?: "The staged restore could not be read",
            )
        }
    }

    private fun writeValidatedCopy(input: InputStream, destination: File, description: String) {
        val temp = File(backupDir, "${destination.name}.tmp")
        try {
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    source.copyBoundedTo(output, MAX_BACKUP_IMPORT_BYTES)
                    output.fd.sync()
                }
            }
            if (temp.length() == 0L) throw IOException("Backup file is empty")
            validateDatabaseFile(temp)
            replaceFileAtomically(temp, destination, description)
        } catch (error: Exception) {
            shredFile(temp)
            deleteDatabaseSidecars(temp)
            throw error
        }
    }

    private fun summarize(file: File, sourceLabel: String): RestoreCandidate =
        DatabaseSecurity.openReadOnly(file, context).use { sqlite ->
            RestoreCandidate(
                sourceLabel = sourceLabel,
                sizeBytes = file.length(),
                schemaVersion = readLong(sqlite, "PRAGMA user_version").toInt(),
                profileCount = countOrZero(sqlite, "profiles"),
                taskCount = countOrZero(sqlite, "tasks"),
                sceneCount = countOrZero(sqlite, "scenes"),
                variableCount = countOrZero(sqlite, "variables"),
                runLogCount = countOrZero(sqlite, "run_logs"),
            )
        }

    private fun countOrZero(sqlite: ReadOnlyDatabase, table: String): Int =
        runCatching { readLong(sqlite, "SELECT COUNT(*) FROM $table").toInt() }.getOrDefault(0)

    suspend fun restore(backupFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val managedBackup = requireManagedBackupFile(backupFile)
            stageRestoreFromInput(managedBackup.inputStream())
        }.map { }
            .onFailure { error ->
                AppLogger.error(tag, "Restore staging failed: ${error.message}", error)
            }
    }

    /**
     * Every restorable file in the backup directory, newest first: backups, the copy a restore
     * replaced, and a pending restore that failed to apply. The last two used to be invisible here,
     * so no listing, retention pass or storage line ever saw them and each one sat at full size
     * until app data was cleared (A-323).
     */
    fun listBackups(): List<File> =
        backupDir.listFiles { file -> isManagedBackupName(databaseName, file.name) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * The newest ordinary backup. Never a restore's safety copy: after a restore that failed to
     * apply, the newest file here is the one that failed, and Setup called it "Latest backup".
     */
    fun latestBackup(): File? = listBackups().firstOrNull { isRegularBackupName(databaseName, it.name) }

    /** The copy of the database the last restore replaced, while it still exists. */
    fun lastRestoreRollback(): RestoreRollback? {
        val prefs = context.getSharedPreferences(RESTORE_PREFS, Context.MODE_PRIVATE)
        return try {
            val name = prefs.getString(restoreRollbackKey(databaseName), null) ?: return null
            val file = File(backupDir, name).takeIf { it.isFile && isManagedBackupName(databaseName, it.name) } ?: return null
            RestoreRollback(file, prefs.getLong(restoredAtKey(databaseName), file.lastModified()))
        } catch (_: ClassCastException) {
            // A record of another type names nothing Undo can use, and left there it would throw
            // at every look.
            clearRestoreRecord(prefs, databaseName)
            null
        }
    }

    fun hasPendingRestore(): Boolean = pendingRestoreFile(context, databaseName).exists()

    /** Removes a backup together with the sidecars validation left beside it. */
    private fun deleteBackupWithSidecars(backup: File): Boolean {
        val deleted = backup.delete()
        deleteDatabaseSidecars(backup)
        return deleted
    }

    /**
     * Applies the snapshot retention window, returning how many files were removed.
     *
     * The decision itself is [selectExpiredSnapshots], which is pure and covered by unit tests; this
     * only maps it onto the managed backup directory.
     */
    fun pruneSnapshots(policy: ConfigurationSnapshotPolicy, nowMs: Long = System.currentTimeMillis()): Int {
        val backups = listBackups()
        val expired = expiredBackupNames(databaseName, backups.map { it.toSnapshotFile() }, policy, nowMs)
        if (expired.isEmpty()) return 0
        return backups.filter { it.name in expired }.count { backup ->
            deleteBackupWithSidecars(backup).also { deleted ->
                if (deleted) AppLogger.info(tag, "Pruned expired snapshot: ${backup.name}")
            }
        }
    }

    private fun stageRestoreFromInput(input: InputStream): File {
        val pending = pendingRestoreFile(context, databaseName)
        val temp = File(backupDir, "${pending.name}.tmp")
        try {
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    source.copyBoundedTo(output, MAX_BACKUP_IMPORT_BYTES)
                    output.fd.sync()
                }
            }
            if (temp.length() == 0L) {
                throw IOException("Backup file is empty")
            }
            validateDatabaseFile(temp)
            replaceFileAtomically(temp, pending, "pending restore")
        } catch (error: Exception) {
            shredFile(temp)
            deleteDatabaseSidecars(temp)
            throw error
        }
        AppLogger.warn(tag, "Restore staged at ${pending.absolutePath}; restart RuleDeck to apply it")
        return pending
    }

    /**
     * Runs [block] over a plaintext, device-independent copy of [managedBackup], then shreds it.
     *
     * Managed backups are SQLCipher ciphertext under a key that dies with the install, so
     * exporting them verbatim produced artifacts that could never be restored anywhere. The
     * portable copy is validated before it leaves the app and only ever exists inside app-private
     * storage.
     */
    private fun <T> withPortableCopy(managedBackup: File, block: (File) -> T): T {
        val portable = File(backupDir, "${managedBackup.name}.portable.tmp")
        try {
            DatabaseSecurity.writePortableCopy(managedBackup, portable, context)
            validateDatabaseFile(portable)
            return block(portable)
        } finally {
            shredFile(portable)
        }
    }

    private fun requireManagedBackupFile(backupFile: File): File {
        val canonicalBackupDir = backupDir.canonicalFile
        val canonicalBackup = backupFile.canonicalFile
        if (!canonicalBackup.path.startsWith(canonicalBackupDir.path + File.separator)) {
            throw SecurityException("Backup file is outside the RuleDeck backup directory")
        }
        if (!canonicalBackup.exists()) {
            throw IOException("Backup file not found: ${backupFile.absolutePath}")
        }
        validateDatabaseFile(canonicalBackup)
        return canonicalBackup
    }

    private fun validateDatabaseFile(file: File) {
        validateDatabaseFile(context, file)
    }

    private fun deleteDatabaseSidecars(file: File) {
        Companion.deleteDatabaseSidecars(file)
    }

    companion object {
        const val DATABASE_NAME = "ruledeck.db"

        /**
         * Applies a staged restore, if there is one.
         *
         * [dropStateOfReplacedDatabase] clears what belonged to the database a restore replaces
         * (the app's plugin condition grants name profile positions in it) and returns whether
         * what must not outlive it is gone from disk. It runs once the restored database is in
         * place and before the journal that records the swap is shredded, so a stop in between or
         * a false return leaves the journal and the next start runs it again. It must be safe to
         * run twice.
         */
        fun applyPendingRestoreIfPresent(
            context: Context,
            databaseName: String = DATABASE_NAME,
            dropStateOfReplacedDatabase: () -> Boolean = { true },
        ): PendingRestoreApplyResult {
            val pending = pendingRestoreFile(context, databaseName)
            val spent = spentRestoreJournal(pending)
            // A journal only goes off its name after the restored database is in place, and the run
            // that moved it shreds it only once the replaced database's state is dropped. One still
            // here means that run stopped after the swap, before that drop was on disk.
            val stoppedAfterSwap = spent.exists()
            if (stoppedAfterSwap && runCatching(dropStateOfReplacedDatabase).getOrDefault(false)) shredFile(spent)
            if (!pending.exists()) {
                return if (stoppedAfterSwap) PendingRestoreApplyResult.StoppedAfterSwap else PendingRestoreApplyResult.NoPending
            }

            val dbFile = context.getDatabasePath(databaseName)
            var rollback: File? = null
            var replacementPublished = false
            var temp: File? = null
            val restorePrefs = context.getSharedPreferences(RESTORE_PREFS, Context.MODE_PRIVATE)
            // What Undo pointed at before this restore, put back if the restore goes back. Read in
            // the try: a value stored with another type throws, and that has to fail this restore
            // rather than escape and leave the journal to throw again at every start.
            var rollbackBefore: String? = null
            var restoredAtBefore = -1L
            var recordRead = false
            var undoWritten = false
            var rollbackRecorded = false

            // Points Undo at the copy this restore replaced, or clears it when there was none.
            fun recordRollback(): Boolean = restorePrefs.edit().run {
                val previous = rollback
                if (previous != null) {
                    putString(restoreRollbackKey(databaseName), previous.name)
                    putLong(restoredAtKey(databaseName), System.currentTimeMillis())
                } else {
                    remove(restoreRollbackKey(databaseName))
                    remove(restoredAtKey(databaseName))
                }
                commit()
            }

            return try {
                rollbackBefore = restorePrefs.getString(restoreRollbackKey(databaseName), null)
                restoredAtBefore = restorePrefs.getLong(restoredAtKey(databaseName), -1L)
                recordRead = true
                validateDatabaseFile(context, pending)
                dbFile.parentFile?.mkdirs()
                rollback = if (dbFile.exists()) {
                    writeRollbackCopy(
                        context,
                        dbFile,
                        File(backupDir(context), "${databaseName.removeSuffix(".db")}_pre_restore_${timestamp()}.db"),
                    )
                } else {
                    null
                }

                temp = File(requireNotNull(dbFile.parentFile), "$databaseName.restore.tmp")
                shredFile(temp)
                if (temp.exists()) {
                    throw IOException("Could not clear interrupted restore staging file")
                }
                pending.inputStream().use { source ->
                    FileOutputStream(temp).use { output ->
                        source.copyBoundedTo(output, MAX_BACKUP_IMPORT_BYTES)
                        output.fd.sync()
                    }
                }
                validateDatabaseFile(context, temp)

                // The pending file is the durable restore journal. Keep it until the same-directory
                // atomic replacement and stale-sidecar cleanup both finish, so a process death can
                // safely retry the transaction on the next startup without a delete/rename gap.
                replaceFileAtomically(temp, dbFile, "live database restore")
                replacementPublished = true
                deleteDatabaseSidecars(dbFile)
                val spent = retireRestoreJournal(pending)
                undoWritten = true
                rollbackRecorded = recordRollback()
                // With no database before, a failed clear would leave Undo on an older restore's
                // copy, and nothing below catches it, so it gets one more try here.
                if (!rollbackRecorded && rollback == null) rollbackRecorded = recordRollback()
                // A write that fails returns false instead of throwing. Unrecorded, the rollback
                // can't be undone from Setup, so the restore goes back as failed. With no database
                // before it there's nothing for Undo to go back to, so the new database stays.
                if (!rollbackRecorded && rollback != null) throw IOException("Could not record the restore's rollback")
                // Only once the rollback is recorded, so a death while it shreds can't lose that,
                // and the replaced database's state is dropped on disk, so a death before that
                // drops it at the next start. A drop that fails mustn't undo the restore.
                if (runCatching(dropStateOfReplacedDatabase).getOrDefault(false)) shredFile(spent)
                PendingRestoreApplyResult.Applied(dbFile, rollback)
            } catch (error: Exception) {
                temp?.let(::shredFile)
                var databaseWentBack = !replacementPublished
                rollback?.takeIf { replacementPublished && it.exists() }?.let { previous ->
                    // A database-sized staging copy, in the databases directory, written on the
                    // path where something has already gone wrong. If the rename below fails the
                    // runCatching swallows it, so without this finally the orphan stays forever
                    // and nothing ever looks for it.
                    val rollbackTemp = File(requireNotNull(dbFile.parentFile), "$databaseName.rollback.tmp")
                    try {
                        databaseWentBack = runCatching {
                            previous.inputStream().use { source ->
                                FileOutputStream(rollbackTemp).use { output ->
                                    source.copyBoundedTo(output, MAX_BACKUP_IMPORT_BYTES)
                                    output.fd.sync()
                                }
                            }
                            replaceFileAtomically(rollbackTemp, dbFile, "restore rollback")
                        }.isSuccess
                    } finally {
                        rollbackTemp.delete()
                        deleteDatabaseSidecars(rollbackTemp)
                    }
                }
                if (undoWritten && databaseWentBack) {
                    // commit() changes the prefs in memory before the write that failed, so Setup
                    // would offer Undo for a restore that has just gone back. When copying the old
                    // database back failed, the restored one is live and its Undo is still right.
                    restorePrefs.edit().run {
                        rollbackBefore?.let { putString(restoreRollbackKey(databaseName), it) } ?: remove(restoreRollbackKey(databaseName))
                        if (restoredAtBefore >= 0) putLong(restoredAtKey(databaseName), restoredAtBefore) else remove(restoredAtKey(databaseName))
                        commit()
                    }
                } else if (replacementPublished && !databaseWentBack && !rollbackRecorded) {
                    // The restored database stays live, so its Undo is the right one, but it isn't
                    // recorded: the write failed, or the journal couldn't be retired before it was
                    // tried. A restart would read the record from before and never offer or clean
                    // up this rollback, so write it now, with a second try as on the way in.
                    runCatching { recordRollback() || recordRollback() }
                } else if (!recordRead) {
                    // A record of another type fails this restore. Cleared, it can't fail the next.
                    clearRestoreRecord(restorePrefs, databaseName)
                }
                val replacementKept = replacementPublished && !databaseWentBack
                // The restored database stays live, so the replaced one's state goes before the
                // journal does.
                val stateDropped = !replacementKept || runCatching(dropStateOfReplacedDatabase).getOrDefault(false)
                val failed = File(backupDir(context), "${databaseName.removeSuffix(".db")}_restore_failed_${timestamp()}.db")
                // The journal may already be off its name if recording the rollback is what failed.
                val journal = pending.takeIf { it.exists() } ?: spentRestoreJournal(pending)
                val failedBackup = if (stateDropped) {
                    if (runCatching { journal.renameTo(failed) }.getOrDefault(false)) {
                        failed
                    } else {
                        shredFile(journal)
                        null
                    }
                } else {
                    // The drop isn't on disk, so a spent journal stays for the next start to finish
                    // it, and the failed copy is a copy.
                    val spent = spentRestoreJournal(pending)
                    if (journal != spent) runCatching { journal.renameTo(spent) }
                    runCatching { spent.copyTo(failed) }.onFailure { failed.delete() }.getOrNull()
                }
                PendingRestoreApplyResult.Failed(error, failedBackup, replacementKept = replacementKept)
            }
        }

        /**
         * Shreds a restore candidate a review left behind, and the staging file an interrupted
         * snapshot decrypt left. A review lives only as long as the process, so at start any
         * candidate is one the user never staged or dismissed, and one from an encrypted snapshot
         * is that snapshot in plaintext.
         */
        fun discardLeftoverRestoreCandidate(context: Context, databaseName: String = DATABASE_NAME) {
            val candidate = candidateRestoreFile(context, databaseName)
            listOf(candidate, File(candidate.parentFile, "${candidate.name}.decrypt.tmp")).forEach { file ->
                shredFile(file)
                deleteDatabaseSidecars(file)
            }
        }

        /** Validated-but-unstaged bytes awaiting the user's explicit Stage decision. */
        fun candidateRestoreFile(context: Context, databaseName: String = DATABASE_NAME): File =
            File(backupDir(context).apply { mkdirs() }, "${databaseName.removeSuffix(".db")}_restore_candidate.db")

        fun pendingRestoreFile(context: Context, databaseName: String = DATABASE_NAME): File =
            File(backupDir(context).apply { mkdirs() }, "${databaseName.removeSuffix(".db")}_restore_pending.db")

        private fun backupDir(context: Context): File = File(context.filesDir, "backups")

        private const val RESTORE_PREFS = "database_restore"
        private val MANAGED_BACKUP_KINDS = listOf("backup", "pre_restore", "restore_failed")

        private fun restoreRollbackKey(databaseName: String) = "rollback_name:$databaseName"

        private fun restoredAtKey(databaseName: String) = "restored_at_ms:$databaseName"

        private fun clearRestoreRecord(prefs: SharedPreferences, databaseName: String) {
            runCatching { prefs.edit().remove(restoreRollbackKey(databaseName)).remove(restoredAtKey(databaseName)).commit() }
        }

        /** An ordinary backup, as opposed to the copies a restore leaves behind. */
        internal fun isRegularBackupName(databaseName: String, fileName: String): Boolean =
            fileName.endsWith(".db") && fileName.startsWith("${databaseName.removeSuffix(".db")}_backup_")

        /**
         * The retention window's verdict, except that the newest ordinary backup always survives.
         * Restore copies share the window, and [selectExpiredSnapshots] keeps only the newest file
         * of any kind, so a fresh failed-restore copy could otherwise push out the last good backup.
         */
        internal fun expiredBackupNames(
            databaseName: String,
            backups: List<SnapshotFile>,
            policy: ConfigurationSnapshotPolicy,
            nowMs: Long,
        ): Set<String> {
            val newestRegular = backups.filter { isRegularBackupName(databaseName, it.name) }
                .maxByOrNull(SnapshotFile::lastModifiedMs)?.name
            return selectExpiredSnapshots(backups, policy, nowMs).mapTo(hashSetOf(), SnapshotFile::name) -
                setOfNotNull(newestRegular)
        }

        internal fun isManagedBackupName(databaseName: String, fileName: String): Boolean {
            val base = databaseName.removeSuffix(".db")
            return fileName.endsWith(".db") && MANAGED_BACKUP_KINDS.any { kind -> fileName.startsWith("${base}_${kind}_") }
        }

        /**
         * Copies the live database into [rollback] before a restore replaces it (A-323). This runs
         * before Room opens, so the WAL can still hold the previous run's last writes; the main file
         * alone missed them, and the WAL is deleted as soon as the replacement lands. A failed copy
         * stops the restore, as it always did. A copy that won't fold or validate is dropped with a
         * warning instead, because the file offered as the way back has to restore, and the user
         * asked to replace this database anyway.
         */
        private fun writeRollbackCopy(context: Context, dbFile: File, rollback: File): File? {
            deleteDatabaseSidecars(rollback)
            dbFile.copyTo(rollback, overwrite = true)
            val wal = File("${dbFile.path}-wal")
            val walCopied = wal.length() > 0L
            if (walCopied) wal.copyTo(File("${rollback.path}-wal"), overwrite = true)
            return try {
                if (walCopied) DatabaseSecurity.foldWalIntoCopy(rollback, context)
                validateDatabaseFile(context, rollback)
                deleteDatabaseSidecars(rollback)
                rollback
            } catch (error: Exception) {
                // Kept, with its WAL, because it is still the only copy of what the restore is about
                // to replace, and a damaged copy can be recovered by hand where a deleted one cannot.
                // It is not offered as a rollback, since staging it would fail the same validation.
                AppLogger.warn(
                    "DatabaseBackupManager",
                    "The copy of the database this restore replaces failed validation. It is kept as " +
                        "${rollback.name} but not offered as a rollback: ${error.message}",
                    error,
                )
                null
            }
        }

        internal const val MAX_BACKUP_BYTES = 104_857_600L
        private const val MAX_BACKUP_IMPORT_BYTES = MAX_BACKUP_BYTES
        private const val WAL_CHECKPOINT_ATTEMPTS = 4
        private const val WAL_CHECKPOINT_RETRY_DELAY_MS = 75L

        private fun timestamp(): String =
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        private fun publishValidatedBackup(context: Context, tempFile: File, backupFile: File) {
            try {
                if (backupFile.exists() && !backupFile.delete()) {
                    throw IOException("Could not replace existing backup file: ${backupFile.absolutePath}")
                }
                if (!tempFile.renameTo(backupFile)) {
                    tempFile.copyTo(backupFile, overwrite = true)
                    tempFile.delete()
                }
                validateDatabaseFile(context, backupFile)
                // Validation opens the copy, so SQLite leaves -wal/-shm beside it; the staging
                // file's sidecars are orphaned by the rename. Neither is part of the backup and
                // neither was ever cleaned up, so they accumulated one set per backup.
                deleteDatabaseSidecars(tempFile)
                deleteDatabaseSidecars(backupFile)
            } catch (error: Exception) {
                backupFile.delete()
                throw error
            }
        }

        private fun validateDatabaseFile(context: Context, file: File) {
            if (!file.exists()) throw IOException("Backup file does not exist")
            DatabaseSecurity.openReadOnly(file, context).use { sqlite ->
                sqlite.rawQuery("PRAGMA integrity_check").use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                        throw IOException("Backup failed SQLite integrity check")
                    }
                }
                val schemaVersion = readLong(sqlite, "PRAGMA user_version").toInt()
                if (schemaVersion !in 1..RULEDECK_DATABASE_SCHEMA_VERSION) {
                    throw IOException(
                        "Backup schema version $schemaVersion is outside the supported range " +
                            "1..$RULEDECK_DATABASE_SCHEMA_VERSION",
                    )
                }
                val requiredSchema = requiredSchemaColumns(schemaVersion)
                val requiredTables = requiredSchema.keys
                sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                    val found = mutableSetOf<String>()
                    while (cursor.moveToNext()) {
                        found += cursor.getString(0)
                    }
                    val missing = requiredTables - found
                    if (missing.isNotEmpty()) {
                        throw IOException(
                            "Backup copy ${file.name} is missing ${countOf(missing.size, "required table")}: ${missing.joinToString()}; " +
                                "the configured database name/path may point at the wrong SQLite file",
                        )
                    }
                }
                requiredSchema.forEach { (table, requiredColumns) ->
                    val foundColumns = tableColumns(sqlite, table)
                    val missingColumns = requiredColumns - foundColumns
                    if (missingColumns.isNotEmpty()) {
                        throw IOException(
                            "Backup schema version $schemaVersion is missing " +
                                "${table}.${missingColumns.joinToString()}",
                        )
                    }
                    val count = readLong(sqlite, "SELECT COUNT(*) FROM $table")
                    if (count < 0) {
                        throw IOException("Backup table $table returned an invalid row count")
                    }
                }
            }
        }

        private fun requiredSchemaColumns(schemaVersion: Int): Map<String, Set<String>> {
            val profiles = mutableSetOf(
                "id",
                "name",
                "enabled",
                "enterTaskId",
                "exitTaskId",
                "cooldownSec",
                "contextsJson",
            )
            if (schemaVersion >= 2) profiles += "automationMode"
            if (schemaVersion >= 5) profiles += "profileGroup"
            if (schemaVersion >= 7) profiles += "requiresRiskAcknowledgement"
            if (schemaVersion >= 14) profiles += "fallbackTaskId"

            val variables = mutableSetOf("name", "value", "isGlobal")
            if (schemaVersion >= 6) variables += "isSecret"

            val runLogs = mutableSetOf(
                "id",
                "taskId",
                "taskName",
                "timestamp",
                "durationMs",
                "success",
                "message",
            )
            if (schemaVersion >= 4) runLogs += setOf("source", "sourceLabel")
            if (schemaVersion >= 11) {
                runLogs += setOf("executionId", "replayOf", "held", "heldPayload", "heldPolicy", "starred")
            }

            return buildMap {
                put("profiles", profiles)
                put("tasks", setOf("id", "name", "priority", "collisionMode", "actionsJson"))
                put("scenes", setOf("id", "name", "widthDp", "heightDp", "elementsJson"))
                put("variables", variables)
                put("run_logs", runLogs)
                if (schemaVersion >= 3) {
                    put("edit_history", setOf("id", "entityType", "entityId", "previousJson", "timestamp"))
                }
                if (schemaVersion >= 15) {
                    put(
                        "execution_journal",
                        setOf(
                            "executionId",
                            "taskId",
                            "taskName",
                            "source",
                            "sourceLabel",
                            "profileId",
                            "replayOf",
                            "parentExecutionId",
                            "producer",
                            "startedAtMs",
                            "updatedAtMs",
                            "lastStepIndex",
                            "lastStepLabel",
                            "state",
                            "terminalReason",
                            "terminalAtMs",
                            "runLogWritten",
                        ),
                    )
                }
            }
        }

        private fun tableColumns(sqlite: ReadOnlyDatabase, table: String): Set<String> =
            sqlite.rawQuery("PRAGMA table_info($table)").use { cursor ->
                val columns = mutableSetOf<String>()
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) {
                    throw IOException("Backup schema query did not return column names for $table")
                }
                while (cursor.moveToNext()) {
                    columns += cursor.getString(nameIndex)
                }
                columns
            }

        private fun readLong(sqlite: ReadOnlyDatabase, sql: String): Long =
            sqlite.rawQuery(sql).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw IOException("Backup query returned no rows: $sql")
                }
                cursor.getLong(0)
            }

        internal fun deleteDatabaseSidecars(dbFile: File) {
            listOf(
                File("${dbFile.path}-wal"),
                File("${dbFile.path}-shm"),
                File("${dbFile.path}-journal"),
            ).forEach { sidecar ->
                if (sidecar.exists()) sidecar.delete()
            }
        }
    }
}

/**
 * Overwrites [file] with zeroes before deleting it.
 *
 * Wear levelling means this cannot guarantee the old blocks are gone, but it does stop the
 * plaintext staging copy from being readable through the file system after an export finishes.
 */
internal fun shredFile(file: File) {
    if (!file.exists()) return
    runCatching {
        val length = file.length()
        // One sync at the end. "rws" synced every 8 KiB write, so a large database took seconds.
        RandomAccessFile(file, "rw").use { handle ->
            val zeroes = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = 0L
            while (written < length) {
                val count = minOf(zeroes.size.toLong(), length - written).toInt()
                handle.write(zeroes, 0, count)
                written += count
            }
            handle.fd.sync()
        }
    }
    file.delete()
}

/**
 * Takes an applied restore journal off its name, so the next start won't apply it again, and
 * returns where it went for [shredFile], which keeps the plaintext a portable backup was staged as
 * from staying in `files/backups`. A death before that shred leaves a [spentRestoreJournal] the
 * next start shreds, never a zeroed journal it would try to apply.
 */
internal fun retireRestoreJournal(pending: File): File {
    val spent = spentRestoreJournal(pending)
    if (!pending.renameTo(spent)) throw IOException("Could not finalize the pending restore journal")
    return spent
}

/** Where [retireRestoreJournal] moves an applied journal until it's shredded. */
internal fun spentRestoreJournal(pending: File): File = File(pending.parentFile, "${pending.name}.spent")

/** Same-directory atomic publication or no publication; never delete the existing target first. */
internal fun replaceFileAtomically(source: File, target: File, purpose: String) {
    target.parentFile?.mkdirs()
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: AtomicMoveNotSupportedException) {
        throw IOException("Atomic $purpose is unavailable; the existing file was left unchanged", error)
    } catch (error: IOException) {
        throw IOException("Atomic $purpose failed; the existing file was left unchanged", error)
    }
}

/**
 * What a candidate (or already-staged) database would install. [error] is set when the file could
 * not be read, so the UI can offer to cancel a staged restore that has since become unreadable
 * rather than leaving it queued to fail at next start.
 */
data class RestoreCandidate(
    val sourceLabel: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val profileCount: Int = 0,
    val taskCount: Int = 0,
    val sceneCount: Int = 0,
    val variableCount: Int = 0,
    val runLogCount: Int = 0,
    val error: String? = null,
) {
    val compatible: Boolean
        get() = error == null && schemaVersion in 1..RULEDECK_DATABASE_SCHEMA_VERSION
}

/** The database a restore replaced, kept so Setup can offer to go back to it. */
data class RestoreRollback(val file: File, val restoredAtMs: Long)

sealed interface PendingRestoreApplyResult {
    data object NoPending : PendingRestoreApplyResult

    /**
     * A restore put its database in place and the app stopped before the restore finished. The
     * restored database is most likely live. It may have gone back if the stop came while a failed
     * restore was being undone, so treating it as live only costs re-granting plugin conditions.
     */
    data object StoppedAfterSwap : PendingRestoreApplyResult
    data class Applied(val databaseFile: File, val previousBackup: File?) : PendingRestoreApplyResult
    /** [replacementKept]: the restored database is live, because there was none before it or the old one couldn't be put back. */
    data class Failed(val exception: Exception, val failedBackup: File?, val replacementKept: Boolean = false) : PendingRestoreApplyResult
}

internal fun InputStream.copyBoundedTo(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return copied
        copied += count
        if (copied > maxBytes) {
            throw IOException("Backup file exceeds ${maxBytes / 1024 / 1024} MB import limit")
        }
        output.write(buffer, 0, count)
    }
}

internal data class WalCheckpointStatus(
    val busy: Int,
    val logFrames: Int,
    val checkpointedFrames: Int,
) {
    val readyForMainFileCopy: Boolean =
        busy == 0 && (logFrames <= 0 || checkpointedFrames >= logFrames)
}

internal fun readWalCheckpointStatus(cursor: Cursor, databaseName: String): WalCheckpointStatus {
    if (!cursor.moveToFirst()) {
        throw IOException("Database WAL checkpoint returned no status for '$databaseName'")
    }
    return WalCheckpointStatus(
        busy = cursor.getInt(0),
        logFrames = cursor.getInt(1),
        checkpointedFrames = cursor.getInt(2),
    )
}

internal fun walCheckpointIncompleteMessage(
    databaseName: String,
    sourcePath: String,
    status: WalCheckpointStatus,
): String =
    "Database WAL checkpoint did not complete for '$databaseName' at $sourcePath before the backup; " +
        "copying the WAL with it " +
        "(busy=${status.busy}, log=${status.logFrames}, checkpointed=${status.checkpointedFrames})"
