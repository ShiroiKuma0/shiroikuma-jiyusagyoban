package com.opentasker.core.storage

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import javax.crypto.KeyGenerator
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistorySnapshotTest {
    @Test
    fun malformedTaskSnapshotFailsClosed() {
        val error = assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.task("{not-json", expectedId = 7)
        }

        assertEquals(EditHistoryDao.TYPE_TASK, error.entityType)
        assertEquals(7L, error.entityId)
    }

    @Test
    fun taskSnapshotWithAnotherIdFailsClosed() {
        val json = StorageJson.encodeToString(Task(id = 8, name = "Other"))

        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.task(json, expectedId = 7)
        }
    }

    @Test
    fun malformedProfileSnapshotFailsClosed() {
        val error = assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.profile("{not-json", expectedId = 7)
        }

        assertEquals(EditHistoryDao.TYPE_PROFILE, error.entityType)
        assertEquals(7L, error.entityId)
    }

    @Test
    fun profileSnapshotWithAnotherIdFailsClosed() {
        val json = StorageJson.encodeToString(Profile(id = 8, name = "Other", enterTaskId = 1))

        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.profile(json, expectedId = 7)
        }
    }

    @Test
    fun malformedSceneSnapshotFailsClosed() {
        val error = assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.scene("{not-json", expectedId = 7)
        }

        assertEquals(EditHistoryDao.TYPE_SCENE, error.entityType)
        assertEquals(7L, error.entityId)
    }

    @Test
    fun sceneSnapshotWithAnotherIdFailsClosed() {
        val json = StorageJson.encodeToString(Scene(id = 8, name = "Other", widthDp = 240, heightDp = 160))

        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.scene(json, expectedId = 7)
        }
    }

    @Test
    fun variableIdentityIsStableAcrossCallsAndSeparatesCompositeKeys() {
        val first = VariableEditHistoryIdentity.entityId(5L, "TOKEN")

        assertEquals(first, VariableEditHistoryIdentity.entityId(5L, "TOKEN"))
        assertTrue(first > 0L)
        assertTrue(first != VariableEditHistoryIdentity.entityId(6L, "TOKEN"))
        assertTrue(first != VariableEditHistoryIdentity.entityId(5L, "OTHER"))
    }

    @Test
    fun encryptedVariableSnapshotRoundTripsAndRejectsAnotherCompositeKey() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val codec = AesGcmVariableSecretCodec(keyProvider = { key })
        val entity = VariableEntity(
            name = "TOKEN",
            value = codec.encrypt(5L, "TOKEN", "secret"),
            isGlobal = true,
            isSecret = true,
            projectId = 5L,
        )
        val entityId = VariableEditHistoryIdentity.entityId(5L, "TOKEN")
        val json = StorageJson.encodeToString(entity)

        assertEquals(entity, EditHistorySnapshotDecoder.variable(json, entityId))
        assertTrue(!json.contains("secret"))
        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.variable(
                json,
                VariableEditHistoryIdentity.entityId(6L, "TOKEN"),
            )
        }
        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.variable(
                StorageJson.encodeToString(entity.copy(value = "plaintext")),
                entityId,
            )
        }
    }

    @Test
    fun projectDeletionSnapshotPinsProjectDestinationAndExactMembership() {
        val snapshot = ProjectDeletionSnapshot(
            project = Project(id = 5L, name = "Work", position = 1),
            targetProjectId = 9L,
            taskIds = listOf(10L),
            profileIds = listOf(11L),
            sceneIds = listOf(12L),
            variableNames = listOf("TOKEN"),
        )

        assertEquals(
            snapshot,
            EditHistorySnapshotDecoder.projectDeletion(StorageJson.encodeToString(snapshot), 5L),
        )
        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.projectDeletion(
                StorageJson.encodeToString(snapshot.copy(taskIds = listOf(10L, 10L))),
                5L,
            )
        }
        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.projectDeletion(StorageJson.encodeToString(snapshot), 6L)
        }
        assertThrows(InvalidEditHistorySnapshotException::class.java) {
            EditHistorySnapshotDecoder.projectDeletion(
                StorageJson.encodeToString(snapshot.copy(project = snapshot.project.copy(name = " Work "))),
                5L,
            )
        }
    }
}
