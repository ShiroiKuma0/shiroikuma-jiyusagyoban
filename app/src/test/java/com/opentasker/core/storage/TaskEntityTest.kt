package com.opentasker.core.storage

import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskEntityTest {
    @Test
    fun taskEntityRoundTripPreservesActions() {
        val task = Task(
            id = 8,
            name = "Notify",
            collisionMode = CollisionMode.WAIT,
        )

        assertEquals(task, task.toEntity().toDomain())
    }

    @Test
    fun malformedActionsJsonReturnsFallbackWithDecodeIssue() {
        val result = TaskEntity(
            id = 9,
            name = "Corrupted task",
            priority = 5,
            collisionMode = CollisionMode.WAIT.name,
            actionsJson = "{not-json",
        ).toDomainDecodeResult()

        assertEquals(emptyList<com.opentasker.core.model.ActionSpec>(), result.value.actions)
        assertEquals(CollisionMode.WAIT, result.value.collisionMode)
        val issue = result.issue
        assertNotNull(issue)
        issue!!
        assertEquals(StorageRecordType.TASK, issue.recordType)
        assertEquals(9L, issue.recordId)
        assertEquals("actionsJson", issue.fieldName)
    }
}
