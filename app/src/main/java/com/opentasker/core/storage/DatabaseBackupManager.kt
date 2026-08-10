package com.opentasker.core.storage

import android.content.Context
import android.net.Uri
import com.opentasker.core.logging.AppLogger
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
                sourceFile.inputStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                validateDatabaseFile(tempFile)
                publishValidatedBackup(context, tempFile, backupFile)
            } catch (error: Exception) {
                tempFile.delete()
                throw error
            }
            AppLogger.info(tag, "Database backed up to ${backupFile.absolutePath}")
            backupFile
        }.onFailure { error ->
            AppLogger.error(tag, "Backup failed: ${error.message}", error)
        }
    }

    private suspend fun checkpointWalBeforeCopy(sourceFile: File) {
        var lastStatus: WalCheckpointStatus? = null
        repeat(WAL_CHECKPOINT_ATTEMPTS) { attempt ->
            val status = db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw IOException("Database WAL checkpoint returned no status for '$databaseName'")
                }
                WalCheckpointStatus(
                    busy = cursor.getInt(0),
                    logFrames = cursor.getInt(1),
                    checkpointedFrames = cursor.getInt(2),
                )
            }
            lastStatus = status
            if (status.readyForMainFileCopy) return
            if (attempt < WAL_CHECKPOINT_ATTEMPTS - 1) {
                delay(WAL_CHECKPOINT_RETRY_DELAY_MS)
            }
        }

        throw IOException(
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

    suspend fun stageEncryptedRestore(uri: Uri, passphrase: CharArray): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val operationContext = currentCoroutineContext()
                val pending = pendingRestoreFile(context, databaseName)
                val temp = File(backupDir, "${pending.name}.decrypt.tmp")
                if (temp.exists() && !temp.delete()) {
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
                    replaceFileAtomically(temp, pending, "pending encrypted restore")
                } catch (error: Throwable) {
                    temp.delete()
                    throw error
                }
                AppLogger.warn(tag, "Encrypted restore staged; restart OpenTasker to apply it")
                pending
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AppLogger.error(tag, "Encrypted restore failed: ${error.message}", error)
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
                candidateRestoreFile(context, databaseName).delete()
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
                candidateRestoreFile(context, databaseName).delete()
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
            AppLogger.warn(tag, "Restore staged at ${pending.absolutePath}; restart OpenTasker to apply it")
            pending
        }.onFailure { error ->
            AppLogger.error(tag, "Restore staging failed: ${error.message}", error)
        }
    }

    /** Drops an inspected candidate the user decided not to stage. */
    fun discardInspectedRestore(): Boolean = candidateRestoreFile(context, databaseName).delete()

    /**
     * Cancels a staged restore. Only the validated pending journal is removed — backups, the live
     * database, and any pre-restore snapshot are untouched.
     */
    fun cancelPendingRestore(): Boolean {
        val pending = pendingRestoreFile(context, databaseName)
        if (!pending.exists()) return false
        return pending.delete().also { deleted ->
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
            temp.delete()
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

    suspend fun stageRestore(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open backup file")
            stageRestoreFromInput(input)
        }.onFailure { error ->
            AppLogger.error(tag, "Restore import failed: ${error.message}", error)
        }
    }

    suspend fun restore(backupFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val managedBackup = requireManagedBackupFile(backupFile)
            stageRestoreFromInput(managedBackup.inputStream())
        }.map { }
            .onFailure { error ->
                AppLogger.error(tag, "Restore staging failed: ${error.message}", error)
            }
    }

    fun listBackups(): List<File> =
        backupDir.listFiles { file ->
            file.name.startsWith("${databaseName.removeSuffix(".db")}_backup_") && file.name.endsWith(".db")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun hasPendingRestore(): Boolean = pendingRestoreFile(context, databaseName).exists()

    fun deleteBackup(backupFile: File): Boolean {
        val managedBackup = runCatching { requireManagedBackupFile(backupFile) }.getOrElse { error ->
            AppLogger.warn(tag, "Refusing to delete unmanaged backup: ${error.message}")
            return false
        }
        return if (managedBackup.delete()) {
            AppLogger.info(tag, "Backup deleted: ${managedBackup.absolutePath}")
            true
        } else {
            AppLogger.warn(tag, "Failed to delete backup: ${managedBackup.absolutePath}")
            false
        }
    }

    fun deleteOldBackups(olderThanDays: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays.coerceAtLeast(1).toLong() * 24 * 60 * 60 * 1000L)
        return listBackups()
            .filter { it.lastModified() < cutoffTime }
            .count { backup ->
                backup.delete().also { deleted ->
                    if (deleted) AppLogger.info(tag, "Deleted old backup: ${backup.name}")
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
            temp.delete()
            throw error
        }
        AppLogger.warn(tag, "Restore staged at ${pending.absolutePath}; restart OpenTasker to apply it")
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
            throw SecurityException("Backup file is outside the OpenTasker backup directory")
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

    companion object {
        const val DATABASE_NAME = "opentasker.db"

        fun applyPendingRestoreIfPresent(
            context: Context,
            databaseName: String = DATABASE_NAME,
        ): PendingRestoreApplyResult {
            val pending = pendingRestoreFile(context, databaseName)
            if (!pending.exists()) return PendingRestoreApplyResult.NoPending

            val dbFile = context.getDatabasePath(databaseName)
            var rollback: File? = null
            var replacementPublished = false
            var temp: File? = null
            return try {
                validateDatabaseFile(context, pending)
                dbFile.parentFile?.mkdirs()
                rollback = if (dbFile.exists()) {
                    File(backupDir(context), "${databaseName.removeSuffix(".db")}_pre_restore_${timestamp()}.db")
                        .also { dbFile.copyTo(it, overwrite = true) }
                } else {
                    null
                }

                temp = File(requireNotNull(dbFile.parentFile), "$databaseName.restore.tmp")
                if (temp.exists() && !temp.delete()) {
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
                if (!pending.delete()) throw IOException("Could not finalize the pending restore journal")
                PendingRestoreApplyResult.Applied(dbFile, rollback)
            } catch (error: Exception) {
                temp?.delete()
                rollback?.takeIf { replacementPublished && it.exists() }?.let { previous ->
                    runCatching {
                        val rollbackTemp = File(requireNotNull(dbFile.parentFile), "$databaseName.rollback.tmp")
                        previous.inputStream().use { source ->
                            FileOutputStream(rollbackTemp).use { output ->
                                source.copyBoundedTo(output, MAX_BACKUP_IMPORT_BYTES)
                                output.fd.sync()
                            }
                        }
                        replaceFileAtomically(rollbackTemp, dbFile, "restore rollback")
                    }
                }
                val failed = File(backupDir(context), "${databaseName.removeSuffix(".db")}_restore_failed_${timestamp()}.db")
                val failedBackup = if (runCatching { pending.renameTo(failed) }.getOrDefault(false)) {
                    failed
                } else {
                    pending.delete()
                    null
                }
                PendingRestoreApplyResult.Failed(error, failedBackup)
            }
        }

        /** Validated-but-unstaged bytes awaiting the user's explicit Stage decision. */
        fun candidateRestoreFile(context: Context, databaseName: String = DATABASE_NAME): File =
            File(backupDir(context).apply { mkdirs() }, "${databaseName.removeSuffix(".db")}_restore_candidate.db")

        fun pendingRestoreFile(context: Context, databaseName: String = DATABASE_NAME): File =
            File(backupDir(context).apply { mkdirs() }, "${databaseName.removeSuffix(".db")}_restore_pending.db")

        private fun backupDir(context: Context): File = File(context.filesDir, "backups")

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
                if (schemaVersion !in 1..OPEN_TASKER_DATABASE_SCHEMA_VERSION) {
                    throw IOException(
                        "Backup schema version $schemaVersion is outside the supported range " +
                            "1..$OPEN_TASKER_DATABASE_SCHEMA_VERSION",
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
                            "Backup copy ${file.name} is missing required table(s): ${missing.joinToString()}; " +
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

        private fun deleteDatabaseSidecars(dbFile: File) {
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
        RandomAccessFile(file, "rws").use { handle ->
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
        get() = error == null && schemaVersion in 1..OPEN_TASKER_DATABASE_SCHEMA_VERSION
}

sealed interface PendingRestoreApplyResult {
    data object NoPending : PendingRestoreApplyResult
    data class Applied(val databaseFile: File, val previousBackup: File?) : PendingRestoreApplyResult
    data class Failed(val exception: Exception, val failedBackup: File?) : PendingRestoreApplyResult
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

internal fun walCheckpointIncompleteMessage(
    databaseName: String,
    sourcePath: String,
    status: WalCheckpointStatus,
): String =
    "Database WAL checkpoint did not complete for '$databaseName' at $sourcePath; " +
        "retry backup after current database reads finish " +
        "(busy=${status.busy}, log=${status.logFrames}, checkpointed=${status.checkpointedFrames})"
