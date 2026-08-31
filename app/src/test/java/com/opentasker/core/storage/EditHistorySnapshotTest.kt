package com.opentasker.core.storage

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
