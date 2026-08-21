package com.opentasker.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val CONFIGURATION_SNAPSHOT_MIME_TYPE = "application/octet-stream"
internal const val CONFIGURATION_SNAPSHOT_PREFIX = "opentasker_snapshot_"
internal const val CONFIGURATION_SNAPSHOT_SUFFIX = ".otbackup"

internal fun configurationSnapshotArchiveName(atMs: Long): String =
    CONFIGURATION_SNAPSHOT_PREFIX + snapshotTimestampFormat().format(Date(atMs)) + CONFIGURATION_SNAPSHOT_SUFFIX

internal fun isConfigurationSnapshotArchive(name: String): Boolean =
    name.startsWith(CONFIGURATION_SNAPSHOT_PREFIX) && name.endsWith(CONFIGURATION_SNAPSHOT_SUFFIX)

internal fun configurationSnapshotTimestamp(name: String): Long? {
    if (!isConfigurationSnapshotArchive(name)) return null
    val encoded = name.removePrefix(CONFIGURATION_SNAPSHOT_PREFIX).removeSuffix(CONFIGURATION_SNAPSHOT_SUFFIX)
    return runCatching { snapshotTimestampFormat().parse(encoded)?.time }.getOrNull()
}

private fun snapshotTimestampFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }

data class ConfigurationSnapshotArchive(
    val uri: Uri,
    val file: SnapshotFile,
)

data class ConfigurationSnapshotArchiveInventory(
    val snapshotCount: Int,
    val storageBytes: Long,
    val removedCount: Int,
)

/** Storage Access Framework boundary for scheduled encrypted snapshot archives. */
// Public because the device lane exercises the SAF archive path; core:storage is a module now.
class ConfigurationSnapshotArchiveStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun requirePersistedAccess(treeUri: Uri) {
        if (treeUri.scheme != "content") {
            throw SnapshotDestinationUnavailableException(DESTINATION_UNAVAILABLE_MESSAGE)
        }
        val granted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
        }
        if (!granted) {
            throw SnapshotDestinationUnavailableException(DESTINATION_UNAVAILABLE_MESSAGE)
        }
    }

    fun createArchive(treeUri: Uri, displayName: String): Uri = destinationOperation {
        requirePersistedAccess(treeUri)
        DocumentsContract.createDocument(
            resolver,
            rootDocumentUri(treeUri),
            CONFIGURATION_SNAPSHOT_MIME_TYPE,
            displayName,
        ) ?: throw SnapshotDestinationUnavailableException(DESTINATION_UNAVAILABLE_MESSAGE)
    }

    fun deleteArchive(treeUri: Uri, archiveUri: Uri): Boolean = destinationOperation {
        requirePersistedAccess(treeUri)
        DocumentsContract.deleteDocument(resolver, archiveUri)
    }

    fun listArchives(treeUri: Uri): List<ConfigurationSnapshotArchive> = destinationOperation {
        requirePersistedAccess(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("The snapshot folder could not be read")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modifiedColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(nameColumn) ?: continue
                    if (!isConfigurationSnapshotArchive(name)) continue
                    val documentId = it.getString(idColumn) ?: continue
                    val providerModified = modifiedColumn.takeIf { column -> column >= 0 && !it.isNull(column) }
                        ?.let(it::getLong)
                        ?.takeIf { timestamp -> timestamp > 0L }
                    val modified = providerModified ?: configurationSnapshotTimestamp(name) ?: 0L
                    val size = sizeColumn.takeIf { column -> column >= 0 && !it.isNull(column) }
                        ?.let(it::getLong)
                        ?.coerceAtLeast(0L)
                        ?: 0L
                    add(
                        ConfigurationSnapshotArchive(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            file = SnapshotFile(name, modified, size),
                        ),
                    )
                }
            }
        }
    }

    fun enforceRetention(
        treeUri: Uri,
        policy: ConfigurationSnapshotPolicy,
        nowMs: Long,
    ): ConfigurationSnapshotArchiveInventory {
        val archives = listArchives(treeUri)
        val expiredNames = selectExpiredSnapshots(archives.map(ConfigurationSnapshotArchive::file), policy, nowMs)
            .mapTo(hashSetOf(), SnapshotFile::name)
        var removed = 0
        archives.filter { it.file.name in expiredNames }.forEach { archive ->
            if (!deleteArchive(treeUri, archive.uri)) {
                throw IOException("The snapshot folder refused to delete ${archive.file.name}")
            }
            removed += 1
        }
        val retained = archives.filterNot { it.file.name in expiredNames }
        return ConfigurationSnapshotArchiveInventory(
            snapshotCount = retained.size,
            storageBytes = retained.sumOf { it.file.sizeBytes },
            removedCount = removed,
        )
    }

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private inline fun <T> destinationOperation(block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        throw SnapshotDestinationUnavailableException(DESTINATION_UNAVAILABLE_MESSAGE, error)
    } catch (error: FileNotFoundException) {
        throw SnapshotDestinationUnavailableException(DESTINATION_UNAVAILABLE_MESSAGE, error)
    }

    companion object {
        const val DESTINATION_UNAVAILABLE_MESSAGE =
            "Snapshot folder access was lost or the folder was removed. Choose it again in Setup."
    }
}

// Public for the same reason: the device lane asserts this is what a missing tree throws.
class SnapshotDestinationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
