package com.opentasker.core.storage

enum class StorageRecordType(val label: String) {
    PROFILE("Profile"),
    TASK("Task"),
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

internal fun Throwable.storageDecodeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
