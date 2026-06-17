package com.opentasker.core.storage

import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileEntityTest {
    @Test
    fun profileEntityRoundTripPreservesAutomationMode() {
        val profile = Profile(
            id = 7,
            name = "Queued profile",
            enterTaskId = 42,
            automationMode = AutomationMode.QUEUED,
        )

        assertEquals(AutomationMode.QUEUED, profile.toEntity().toDomain().automationMode)
    }

    @Test
    fun unknownAutomationModeFallsBackToSingle() {
        val entity = ProfileEntity(
            id = 1,
            name = "Legacy profile",
            enabled = true,
            enterTaskId = 2,
            exitTaskId = null,
            cooldownSec = 0,
            contextsJson = "[]",
            automationMode = "UNKNOWN",
        )

        assertEquals(AutomationMode.SINGLE, entity.toDomain().automationMode)
    }

    @Test
    fun malformedContextsJsonReturnsFallbackWithDecodeIssue() {
        val result = ProfileEntity(
            id = 5,
            name = "Corrupted profile",
            enabled = true,
            enterTaskId = 2,
            exitTaskId = null,
            cooldownSec = 0,
            contextsJson = "{not-json",
        ).toDomainDecodeResult()

        assertEquals(emptyList<com.opentasker.core.model.ContextSpec>(), result.value.contexts)
        val issue = result.issue
        assertNotNull(issue)
        issue!!
        assertEquals(StorageRecordType.PROFILE, issue.recordType)
        assertEquals(5L, issue.recordId)
        assertEquals("contextsJson", issue.fieldName)
    }
}
