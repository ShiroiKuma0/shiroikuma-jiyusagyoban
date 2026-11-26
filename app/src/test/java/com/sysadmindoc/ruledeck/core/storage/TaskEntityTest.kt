package com.sysadmindoc.ruledeck.core.storage

import com.sysadmindoc.ruledeck.core.model.CollisionMode
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskEntityTest {
    @Test
    fun taskEntityRoundTripPreservesActions() {
        val task = Task(
            id = 8,
            name = "Notify",
            collisionMode = CollisionMode.WAIT,
            actions = listOf(
                ActionSpec(
                    id = 3,
                    type = "notify.show",
                    args = mapOf("text" to "hello"),
                    continueOnError = true,
                    condition = "%armed == true",
                ),
            ),
        )

        assertEquals(task, task.toEntity().toDomain())
    }

    @Test
    fun malformedActionsJsonReturnsFallbackWithDecodeIssue() {
        val entity = TaskEntity(
            id = 9,
            name = "Corrupted task",
            priority = 5,
            collisionMode = CollisionMode.WAIT.name,
            actionsJson = "{not-json",
        )
        val result = entity.toDomainDecodeResult()

        assertEquals(emptyList<com.sysadmindoc.ruledeck.core.model.ActionSpec>(), result.value.actions)
        assertEquals(CollisionMode.WAIT, result.value.collisionMode)
        val issue = result.issue
        assertNotNull(issue)
        issue!!
        assertEquals(StorageRecordType.TASK, issue.recordType)
        assertEquals(9L, issue.recordId)
        assertEquals("actionsJson", issue.fieldName)
        assertThrows(CorruptStoredRecordException::class.java) { entity.toDomain() }
    }
}
