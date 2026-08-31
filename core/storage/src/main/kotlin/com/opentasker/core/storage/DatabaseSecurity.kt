package com.opentasker.core.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Read-only access to a candidate database file for backup validation.
 *
 * Fork note: upstream 0.2.80 encrypts the whole Room database with SQLCipher, and this object owned
 * the plaintext→encrypted conversion. The fork deliberately keeps the database in plaintext (白い熊,
 * 2026-08-03), so only the read-only opener that upstream's backup review depends on survives here.
 * The app sandbox and Android's file-based encryption already protect the live file, while a
 * plaintext database keeps backups portable across installs and inspectable offline — neither of
 * which survives a per-install Keystore key that fails closed.
 */
internal object DatabaseSecurity {
    internal fun openReadOnly(file: File, @Suppress("UNUSED_PARAMETER") context: Context): ReadOnlyDatabase {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return ReadOnlyDatabase(
            query = { sql -> database.rawQuery(sql, null) },
            closeAction = database::close,
        )
    }

    /**
     * Replays the `-wal` copied beside [copy] into [copy] itself, so the backup is one file again.
     *
     * Upstream's A-324 consistent-snapshot copy, kept to its plaintext branch: the fork's database
     * is never SQLCipher. Opening the copy read-write lets SQLite recover the WAL, and a TRUNCATE
     * checkpoint then writes every committed frame into the main file. [copy] must be a private
     * staging file that nothing else has open.
     */
    internal fun foldWalIntoCopy(copy: File, @Suppress("UNUSED_PARAMETER") context: Context) {
        val status = SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { readWalCheckpointStatus(it, copy.name) }
        }
        if (!status.readyForMainFileCopy) {
            throw IOException(
                "Could not fold the copied WAL into ${copy.name} " +
                    "(busy=${status.busy}, log=${status.logFrames}, checkpointed=${status.checkpointedFrames})",
            )
        }
        DatabaseBackupManager.deleteDatabaseSidecars(copy)
    }
}

internal class ReadOnlyDatabase(
    private val query: (String) -> Cursor,
    private val closeAction: () -> Unit,
) : Closeable {
    fun rawQuery(sql: String): Cursor = query(sql)

    override fun close() = closeAction()
}
