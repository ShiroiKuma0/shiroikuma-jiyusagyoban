package com.opentasker.core.storage

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task

/**
 * Raised when an edit-history snapshot cannot be restored safely.
 *
 * The snapshot is untrusted persisted data. Callers must not substitute it into an entity's
 * payload column when decoding fails or when it belongs to another entity.
 */
class InvalidEditHistorySnapshotException(
    val entityType: String,
    val entityId: Long,
    reason: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "Cannot restore $entityType #$entityId from edit history: $reason.",
    cause,
)

/** Strict decoders used by the undo/redo transaction before any entity write occurs. */
object EditHistorySnapshotDecoder {
    fun task(json: String, expectedId: Long): Task = decode(
        entityType = EditHistoryDao.TYPE_TASK,
        expectedId = expectedId,
        json = json,
        id = { it.id },
    )

    fun profile(json: String, expectedId: Long): Profile = decode(
        entityType = EditHistoryDao.TYPE_PROFILE,
        expectedId = expectedId,
        json = json,
        id = { it.id },
    )

    fun scene(json: String, expectedId: Long): Scene = decode(
        entityType = EditHistoryDao.TYPE_SCENE,
        expectedId = expectedId,
        json = json,
        id = { it.id },
    )

    private inline fun <reified T> decode(
        entityType: String,
        expectedId: Long,
        json: String,
        id: (T) -> Long,
    ): T {
        val decoded = runCatching { StorageJson.decodeFromString<T>(json) }
            .getOrElse { error ->
                throw InvalidEditHistorySnapshotException(
                    entityType = entityType,
                    entityId = expectedId,
                    reason = "the snapshot is not valid $entityType data",
                    cause = error,
                )
            }
        if (id(decoded) != expectedId) {
            throw InvalidEditHistorySnapshotException(
                entityType = entityType,
                entityId = expectedId,
                reason = "the snapshot belongs to entity #${id(decoded)}",
            )
        }
        return decoded
    }
}
