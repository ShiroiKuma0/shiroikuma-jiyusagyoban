package com.opentasker.core.storage

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.Closeable
import java.nio.charset.StandardCharsets
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/** Loads SQLCipher and upgrades an existing plaintext Room file before Room can open it. */
internal object DatabaseSecurity {
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    private const val TEMP_SUFFIX = ".encrypted.tmp"
    private const val USER_TABLE_COUNT_SQL =
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"

    private fun userTableCount(database: CipherDatabase): Int =
        database.rawQuery(USER_TABLE_COUNT_SQL, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Volatile
    private var cipherLoaded = false

    @Synchronized
    fun prepareEncryptedDatabase(context: Context, databaseName: String): ByteArray {
        loadCipher()
        val databaseKey = DatabaseKeyStore.getOrCreate(context)
        val databaseFile = context.getDatabasePath(databaseName)
        if (databaseFile.exists() && isPlaintext(databaseFile)) {
            migratePlaintextDatabase(databaseFile, databaseKey)
        }
        return databaseKey
    }

    fun openEncryptedReadOnly(file: File, context: Context): CipherDatabase {
        loadCipher()
        return CipherDatabase.openDatabase(
            file.absolutePath,
            DatabaseKeyStore.getOrCreate(context),
            null,
            CipherDatabase.OPEN_READONLY,
            null,
        )
    }

    internal fun openReadOnly(file: File, context: Context): ReadOnlyDatabase {
        if (isPlaintext(file)) {
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

        val database = openEncryptedReadOnly(file, context)
        return ReadOnlyDatabase(
            query = { sql -> database.rawQuery(sql, null) },
            closeAction = database::close,
        )
    }

    internal fun isPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return FileInputStream(file).use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return@use false
                if (count == 0) continue
                offset += count
            }
            header.contentEquals(SQLITE_HEADER)
        }
    }

    /**
     * Writes a plaintext SQLite copy of [source] to [destination].
     *
     * Every managed backup is SQLCipher ciphertext keyed by [DatabaseKeyStore], whose key is
     * destroyed on uninstall and never migrates between devices — so exporting those bytes
     * produces an artifact nobody can ever open again, which is precisely the reinstall and
     * device-transfer case backups exist for. Exports go through this instead, and the restore
     * side already re-encrypts a plaintext file under the new install's key on first open
     * ([prepareEncryptedDatabase]).
     *
     * Callers own [destination] and must shred it once the export layer has consumed it.
     */
    internal fun writePortableCopy(source: File, destination: File, context: Context) {
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not clear the previous portable export staging file")
        }
        if (isPlaintext(source)) {
            source.copyTo(destination, overwrite = true)
            return
        }

        loadCipher()
        val databaseKey = DatabaseKeyStore.getOrCreate(context)
        try {
            // SQLite opens attached databases with the main connection's flags, and the source is
            // opened without CREATE — so ATTACH cannot create the target itself and fails with
            // SQLITE_CANTOPEN. A zero-length file is a valid empty database to attach to.
            destination.parentFile?.mkdirs()
            if (!destination.createNewFile()) {
                throw IOException("Could not create the portable export staging file")
            }
            CipherDatabase.openDatabase(
                source.absolutePath,
                databaseKey.copyOf(),
                null,
                CipherDatabase.OPEN_READWRITE,
                null,
            ).use { encrypted ->
                val sourceVersion = encrypted.version
                encrypted.execSQL(
                    "ATTACH DATABASE ? AS portable KEY ?",
                    arrayOf(destination.absolutePath, ""),
                )
                try {
                    encrypted.rawQuery("SELECT sqlcipher_export('portable')", null).use { it.moveToFirst() }
                    // sqlcipher_export copies schema and rows but not user_version, and the restore
                    // validator reads user_version to decide whether the file is supported at all.
                    encrypted.execSQL("PRAGMA portable.user_version = $sourceVersion")
                } finally {
                    encrypted.execSQL("DETACH DATABASE portable")
                }
            }
            deleteDatabaseSidecars(destination)
            if (!isPlaintext(destination)) {
                throw IOException("Portable export did not produce a readable SQLite file")
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    internal fun deleteDatabaseSidecars(databaseFile: File) {
        listOf(
            File("${databaseFile.path}-wal"),
            File("${databaseFile.path}-shm"),
            File("${databaseFile.path}-journal"),
        ).forEach { sidecar ->
            if (sidecar.exists() && !sidecar.delete()) {
                throw IOException("Could not remove stale database sidecar ${sidecar.name}")
            }
        }
    }

    private fun loadCipher() {
        if (cipherLoaded) return
        synchronized(this) {
            if (!cipherLoaded) {
                System.loadLibrary("sqlcipher")
                cipherLoaded = true
            }
        }
    }

    private fun migratePlaintextDatabase(databaseFile: File, databaseKey: ByteArray) {
        var sourceTables = 0
        val sourceVersion = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { source ->
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            source.rawQuery(USER_TABLE_COUNT_SQL, null).use { cursor ->
                if (cursor.moveToFirst()) sourceTables = cursor.getInt(0)
            }
            source.version
        }

        val temporaryFile = File(databaseFile.parentFile, databaseFile.name + TEMP_SUFFIX)
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Could not clear interrupted encrypted database migration")
        }

        try {
            CipherDatabase.openDatabase(
                temporaryFile.absolutePath,
                databaseKey.copyOf(),
                null,
                CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY,
                null,
            ).use { encrypted ->
                encrypted.execSQL(
                    "ATTACH DATABASE ? AS plaintext KEY ?",
                    arrayOf(databaseFile.absolutePath, ""),
                )
                try {
                    // sqlcipher_export(target[, source]) defaults source to 'main'. 'main' here is
                    // the freshly created encrypted file, so the one-argument form copied the empty
                    // database onto itself and dropped every table the user had.
                    encrypted.rawQuery("SELECT sqlcipher_export('main', 'plaintext')", null).use { it.moveToFirst() }
                } finally {
                    encrypted.execSQL("DETACH DATABASE plaintext")
                }
                encrypted.version = sourceVersion
                check(encrypted.isDatabaseIntegrityOk) { "Migrated database failed SQLCipher integrity check" }
                // An empty database is structurally valid, so integrity alone cannot tell a real
                // migration apart from one that copied nothing.
                val copied = userTableCount(encrypted)
                check(copied >= sourceTables) {
                    "Encrypted migration copied $copied of $sourceTables tables; refusing to publish it"
                }
            }

            deleteDatabaseSidecars(databaseFile)
            replaceFileAtomically(temporaryFile, databaseFile, "encrypted database migration")
            deleteDatabaseSidecars(databaseFile)
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }
}

internal class ReadOnlyDatabase(
    private val query: (String) -> Cursor,
    private val closeAction: () -> Unit,
) : Closeable {
    fun rawQuery(sql: String): Cursor = query(sql)

    override fun close() = closeAction()
}
