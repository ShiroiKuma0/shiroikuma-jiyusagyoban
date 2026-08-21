package com.opentasker.core.storage

import kotlinx.serialization.json.Json

/**
 * Shared JSON codec for stored automation payloads (task actions, profile contexts, scene
 * elements). `ignoreUnknownKeys` keeps valid older/newer payloads that carry additive fields
 * decodable instead of being misclassified as corrupt, while structurally invalid JSON still
 * fails closed with a [StorageDecodeIssue].
 */
val StorageJson: Json = Json { ignoreUnknownKeys = true }

enum class StorageRecordType(val label: String) {
    PROFILE("Profile"),
    TASK("Task"),
    SCENE("Scene"),
}

data class StorageDecodeIssue(
    val recordType: StorageRecordType,
    val recordId: Long,
    val recordName: String,
    val fieldName: String,
    val message: String,
)

data class StorageDecodeResult<T>(
    val value: T,
    val issue: StorageDecodeIssue? = null,
)

/**
 * Raised when a caller asks to use a stored domain object whose serialized payload is corrupt.
 * The raw entity remains untouched so database backup/restore can still recover it.
 */
class CorruptStoredRecordException(
    val issue: StorageDecodeIssue,
) : IllegalStateException(issue.recoveryMessage())

fun StorageDecodeIssue.recoveryMessage(): String =
    "${recordType.label} \"$recordName\" (#$recordId) has corrupt $fieldName data. " +
        "Restore a database backup before continuing; the raw stored data was left unchanged."

fun <T> StorageDecodeResult<T>.requireDecoded(): T =
    issue?.let { throw CorruptStoredRecordException(it) } ?: value

internal fun Throwable.storageDecodeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
