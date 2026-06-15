package com.opentasker.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.opentasker.core.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
            val sourceFile = context.getDatabasePath(databaseName)
            if (!sourceFile.exists()) {
                throw IOException("Database file does not exist")
            }

            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
            val backupFile = File(backupDir, "${databaseName.removeSuffix(".db")}_backup_${timestamp()}.db")
            sourceFile.inputStream().use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }
            validateDatabaseFile(backupFile)
            AppLogger.info(tag, "Database backed up to ${backupFile.absolutePath}")
            backupFile
        }.onFailure { error ->
            AppLogger.error(tag, "Backup failed: ${error.message}", error)
        }
    }

    suspend fun exportBackup(backupFile: File, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val managedBackup = requireManagedBackupFile(backupFile)
            val output = context.contentResolver.openOutputStream(destination)
                ?: throw IOException("Could not open export destination")
            managedBackup.inputStream().use { input ->
                output.use { stream ->
                    input.copyTo(stream)
                }
            }
            AppLogger.info(tag, "Database backup exported to $destination")
        }.onFailure { error ->
            AppLogger.error(tag, "Backup export failed: ${error.message}", error)
        }
    }

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
        input.use { source ->
            FileOutputStream(temp).use { output ->
                source.copyTo(output)
            }
        }
        if (temp.length() == 0L) {
            temp.delete()
            throw IOException("Backup file is empty")
        }
        validateDatabaseFile(temp)
        temp.copyTo(pending, overwrite = true)
        temp.delete()
        AppLogger.warn(tag, "Restore staged at ${pending.absolutePath}; restart 白い熊 自由作業盤 to apply it")
        return pending
    }

    private fun requireManagedBackupFile(backupFile: File): File {
        val canonicalBackupDir = backupDir.canonicalFile
        val canonicalBackup = backupFile.canonicalFile
        if (!canonicalBackup.path.startsWith(canonicalBackupDir.path + File.separator)) {
            throw SecurityException("Backup file is outside the 白い熊 自由作業盤 backup directory")
        }
        if (!canonicalBackup.exists()) {
            throw IOException("Backup file not found: ${backupFile.absolutePath}")
        }
        validateDatabaseFile(canonicalBackup)
        return canonicalBackup
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
            return try {
                validateDatabaseFile(pending)
                dbFile.parentFile?.mkdirs()
                rollback = if (dbFile.exists()) {
                    File(backupDir(context), "${databaseName.removeSuffix(".db")}_pre_restore_${timestamp()}.db")
                        .also { dbFile.copyTo(it, overwrite = true) }
                } else {
                    null
                }

                val temp = File(requireNotNull(dbFile.parentFile), "$databaseName.restore.tmp")
                pending.copyTo(temp, overwrite = true)
                validateDatabaseFile(temp)
                deleteDatabaseSidecars(dbFile)
                if (dbFile.exists() && !dbFile.delete()) {
                    throw IOException("Could not replace existing database")
                }
                if (!temp.renameTo(dbFile)) {
                    temp.copyTo(dbFile, overwrite = true)
                    temp.delete()
                }
                deleteDatabaseSidecars(dbFile)
                pending.delete()
                PendingRestoreApplyResult.Applied(dbFile, rollback)
            } catch (error: Exception) {
                rollback?.takeIf { it.exists() }?.let { previous ->
                    runCatching {
                        dbFile.parentFile?.mkdirs()
                        previous.copyTo(dbFile, overwrite = true)
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

        fun pendingRestoreFile(context: Context, databaseName: String = DATABASE_NAME): File =
            File(backupDir(context).apply { mkdirs() }, "${databaseName.removeSuffix(".db")}_restore_pending.db")

        private fun backupDir(context: Context): File = File(context.filesDir, "backups")

        private fun timestamp(): String =
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        private fun validateDatabaseFile(file: File) {
            if (!file.exists()) throw IOException("Backup file does not exist")
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                        throw IOException("Backup failed SQLite integrity check")
                    }
                }
                val requiredTables = setOf("profiles", "tasks", "run_logs")
                sqlite.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('profiles','tasks','run_logs')",
                    null,
                ).use { cursor ->
                    val found = mutableSetOf<String>()
                    while (cursor.moveToNext()) {
                        found += cursor.getString(0)
                    }
                    val missing = requiredTables - found
                    if (missing.isNotEmpty()) {
                        throw IOException("Backup is missing required table(s): ${missing.joinToString()}")
                    }
                }
            }
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

sealed interface PendingRestoreApplyResult {
    data object NoPending : PendingRestoreApplyResult
    data class Applied(val databaseFile: File, val previousBackup: File?) : PendingRestoreApplyResult
    data class Failed(val exception: Exception, val failedBackup: File?) : PendingRestoreApplyResult
}
