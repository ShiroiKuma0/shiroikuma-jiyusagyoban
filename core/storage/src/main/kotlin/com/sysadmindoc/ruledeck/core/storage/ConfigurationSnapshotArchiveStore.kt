package com.sysadmindoc.ruledeck.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val CONFIGURATION_SNAPSHOT_MIME_TYPE = "application/octet-stream"
internal const val CONFIGURATION_SNAPSHOT_PREFIX = "ruledeck_snapshot_"
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
    /** Expired archives the folder wouldn't delete. They're still there, so they count above. */
    val refusedCount: Int = 0,
)

/**
 * Deletes the [archives] that [policy] lets go, one at a time. A delete that returns false or
 * throws is counted as refused and that archive stays in the totals, so the run that just wrote a
 * good snapshot still succeeds. Failing it made WorkManager retry, and every retry wrote another
 * full archive. Losing access to the folder still stops the run.
 */
internal fun <T> applySnapshotRetention(
    archives: List<T>,
    fileOf: (T) -> SnapshotFile,
    policy: ConfigurationSnapshotPolicy,
    nowMs: Long,
    onDeleteError: (T, Exception) -> Unit = { _, _ -> },
    delete: (T) -> Boolean,
): ConfigurationSnapshotArchiveInventory {
    val expired = selectExpiredSnapshots(archives.map(fileOf), policy, nowMs).mapTo(hashSetOf(), SnapshotFile::name)
    var removed = 0
    var refused = 0
    val kept = archives.filter { archive ->
        if (fileOf(archive).name !in expired) return@filter true
        val deleted = try {
            delete(archive)
        } catch (error: SnapshotDestinationUnavailableException) {
            throw error
        } catch (error: Exception) {
            onDeleteError(archive, error)
            false
        }
        if (deleted) removed += 1 else refused += 1
        !deleted
    }
    return ConfigurationSnapshotArchiveInventory(
        snapshotCount = kept.size,
        storageBytes = kept.sumOf { fileOf(it).sizeBytes },
        removedCount = removed,
        refusedCount = refused,
    )
}

/**
 * What one old archive's lost-access error means, with the folder listed a moment before. While
 * the grant is still held, a file not found was already gone (true, as deleted) and anything else
 * is that one file refused (false). Without the grant the folder itself is lost, and that stops
 * the run as before.
 */
internal fun singleArchiveDeleteOutcome(error: SnapshotDestinationUnavailableException, stillGranted: Boolean): Boolean {
    if (!stillGranted) throw error
    return error.cause is FileNotFoundException
}

/**
 * Runs [retain] after a good export. Retention that can't finish, say because the folder couldn't
 * be listed, is reported through [onError] and [fallback] stands in for its counts, so the run
 * still succeeds: failing it made WorkManager retry, and every retry wrote another full archive.
 * Losing access to the folder still stops the run.
 */
internal inline fun retainAfterExport(
    fallback: () -> ConfigurationSnapshotArchiveInventory,
    onError: (Exception) -> Unit,
    retain: () -> ConfigurationSnapshotArchiveInventory,
): ConfigurationSnapshotArchiveInventory = try {
    retain()
} catch (error: SnapshotDestinationUnavailableException) {
    throw error
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    onError(error)
    fallback()
}

/**
 * What a run that saved its snapshot but couldn't list the folder records: the last run's counts
 * with one more for the archive it just wrote (its size isn't known without the listing), and the
 * old ones the folder still holds after refusing to delete them. The next run that can list the
 * folder corrects them.
 */
internal fun inventoryWithoutListing(lastRun: ConfigurationSnapshotStatus) = ConfigurationSnapshotArchiveInventory(
    snapshotCount = lastRun.snapshotCount + 1,
    storageBytes = lastRun.storageBytes,
    removedCount = 0,
    refusedCount = lastRun.refusedDeletes,
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
    ): ConfigurationSnapshotArchiveInventory = applySnapshotRetention(
        archives = listArchives(treeUri),
        fileOf = ConfigurationSnapshotArchive::file,
        policy = policy,
        nowMs = nowMs,
        onDeleteError = { archive, error ->
            AppLogger.warn("ConfigurationSnapshotArchiveStore", "Deleting old snapshot ${archive.file.name} failed", error)
        },
    ) { archive ->
        try {
            deleteArchive(treeUri, archive.uri)
        } catch (error: SnapshotDestinationUnavailableException) {
            val stillGranted = runCatching { requirePersistedAccess(treeUri) }.isSuccess
            singleArchiveDeleteOutcome(error, stillGranted).also { gone ->
                if (!gone) {
                    AppLogger.warn("ConfigurationSnapshotArchiveStore", "The folder refused to delete old snapshot ${archive.file.name}", error)
                }
            }
        }
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
            "Snapshot folder access was lost or the folder was removed. Choose it again in Settings."
    }
}

// Public for the same reason: the device lane asserts this is what a missing tree throws.
class SnapshotDestinationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
