package com.opentasker.core.storage

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDeletionSnapshot(
    val project: Project,
    val targetProjectId: Long,
    val taskIds: List<Long> = emptyList(),
    val profileIds: List<Long> = emptyList(),
    val sceneIds: List<Long> = emptyList(),
    val variableNames: List<String> = emptyList(),
)

/** Stable edit-history identity for Room variables, whose real key is `(projectId, name)`. */
object VariableEditHistoryIdentity {
    fun entityId(projectId: Long, name: String): Long {
        require(projectId > 0L) { "Variable project id must be positive." }
        val normalized = VariableNamePolicy.normalize(name)
        require(normalized == name) { "Variable name must be normalized before history is recorded." }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(projectId).array())
            update(0)
            update(name.toByteArray(StandardCharsets.UTF_8))
        }.digest()
        val positive = ByteBuffer.wrap(digest).long and Long.MAX_VALUE
        return positive.takeIf { it != 0L } ?: 1L
    }
}

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

    fun variable(json: String, expectedId: Long): VariableEntity {
        val decoded = decode<VariableEntity>(
            entityType = EditHistoryDao.TYPE_VARIABLE,
            expectedId = expectedId,
            json = json,
            id = { VariableEditHistoryIdentity.entityId(it.projectId, it.name) },
        )
        if (decoded.isSecret && !AesGcmVariableSecretCodec.isEnvelope(decoded.value)) {
            invalid(
                entityType = EditHistoryDao.TYPE_VARIABLE,
                entityId = expectedId,
                reason = "a secret snapshot does not contain an encrypted envelope",
            )
        }
        return decoded
    }

    fun projectDeletion(json: String, expectedId: Long): ProjectDeletionSnapshot {
        val decoded = decode<ProjectDeletionSnapshot>(
            entityType = EditHistoryDao.TYPE_PROJECT,
            expectedId = expectedId,
            json = json,
            id = { it.project.id },
        )
        val invalidReason = when {
            decoded.project.id <= 0L -> "the project id is invalid"
            decoded.project.id == DEFAULT_PROJECT_ID -> "the Default project cannot be deleted"
            decoded.project.name.trim() != decoded.project.name ||
                decoded.project.name.isEmpty() ||
                decoded.project.name.length > 64 -> "the project name is invalid"
            decoded.project.position < 0 -> "the project position is invalid"
            decoded.targetProjectId <= 0L || decoded.targetProjectId == decoded.project.id ->
                "the destination project is invalid"
            !decoded.taskIds.validEntityIds() -> "the task membership is invalid"
            !decoded.profileIds.validEntityIds() -> "the profile membership is invalid"
            !decoded.sceneIds.validEntityIds() -> "the scene membership is invalid"
            decoded.variableNames.distinct().size != decoded.variableNames.size ->
                "the variable membership contains duplicates"
            decoded.variableNames.any { VariableNamePolicy.normalize(it) != it } ->
                "the variable membership contains an invalid name"
            else -> null
        }
        invalidReason?.let { reason ->
            invalid(EditHistoryDao.TYPE_PROJECT, expectedId, reason)
        }
        return decoded
    }

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
        val actualId = runCatching { id(decoded) }
            .getOrElse { error ->
                invalid(
                    entityType = entityType,
                    entityId = expectedId,
                    reason = "the snapshot has an invalid identity",
                    cause = error,
                )
            }
        if (actualId != expectedId) {
            throw InvalidEditHistorySnapshotException(
                entityType = entityType,
                entityId = expectedId,
                reason = "the snapshot belongs to entity #$actualId",
            )
        }
        return decoded
    }

    private fun invalid(
        entityType: String,
        entityId: Long,
        reason: String,
        cause: Throwable? = null,
    ): Nothing = throw InvalidEditHistorySnapshotException(entityType, entityId, reason, cause)

    private fun List<Long>.validEntityIds(): Boolean =
        all { it > 0L } && distinct().size == size
}
