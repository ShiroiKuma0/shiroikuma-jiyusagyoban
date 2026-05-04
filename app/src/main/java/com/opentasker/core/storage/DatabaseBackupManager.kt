package com.opentasker.core.storage

import android.content.Context
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles database backup and restore operations.
 * Stores backups in app-specific storage with timestamp.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    private val context: Context,
    private val db: AppDatabase
) {
    private val tag = "DatabaseBackupManager"
    private val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    
    /**
     * Create a backup of the current database.
     */
    suspend fun backup(): Result<File> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Close the database first
            db.close()
            
            val sourceFile = context.getDatabasePath("opentasker.db")
            val timestamp = dateFormat.format(Date())
            val backupFile = File(backupDir, "opentasker_backup_$timestamp.db")
            
            sourceFile.inputStream().use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            AppLogger.info(tag, "Database backed up to ${backupFile.absolutePath}")
            Result.success(backupFile)
        } catch (e: Exception) {
            AppLogger.error(tag, "Backup failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Restore database from a backup file.
     */
    suspend fun restore(backupFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!backupFile.exists()) {
                return@withContext Result.failure(Exception("Backup file not found: ${backupFile.absolutePath}"))
            }
            
            // Close the database first
            db.close()
            
            val targetFile = context.getDatabasePath("opentasker.db")
            
            backupFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            AppLogger.info(tag, "Database restored from ${backupFile.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.error(tag, "Restore failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * List all available backups.
     */
    fun listBackups(): List<File> {
        return backupDir.listFiles { file ->
            file.name.startsWith("opentasker_backup_") && file.name.endsWith(".db")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Delete a specific backup file.
     */
    fun deleteBackup(backupFile: File): Boolean {
        return if (backupFile.delete()) {
            AppLogger.info(tag, "Backup deleted: ${backupFile.absolutePath}")
            true
        } else {
            AppLogger.warn(tag, "Failed to delete backup: ${backupFile.absolutePath}")
            false
        }
    }
    
    /**
     * Delete all backups older than specified days.
     */
    fun deleteOldBackups(olderThanDays: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        val backups = listBackups()
        var deleted = 0
        
        for (backup in backups) {
            if (backup.lastModified() < cutoffTime) {
                if (backup.delete()) {
                    deleted++
                    AppLogger.info(tag, "Deleted old backup: ${backup.name}")
                }
            }
        }
        
        return deleted
    }
}

/**
 * Result wrapper for backup/restore operations.
 */
sealed class Result<T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure<T>(val exception: Exception) : Result<T>()
    
    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun <T> failure(exception: Exception): Result<T> = Failure(exception)
    }
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun exceptionOrNull(): Exception? = when (this) {
        is Success -> null
        is Failure -> exception
    }
}
