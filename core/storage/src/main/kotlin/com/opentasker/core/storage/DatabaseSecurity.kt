package com.opentasker.core.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

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
}

internal class ReadOnlyDatabase(
    private val query: (String) -> Cursor,
    private val closeAction: () -> Unit,
) : Closeable {
    fun rawQuery(sql: String): Cursor = query(sql)

    override fun close() = closeAction()
}
