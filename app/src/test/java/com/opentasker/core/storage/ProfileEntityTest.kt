package com.opentasker.core.storage

import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEntityTest {
    @Test
    fun profileEntityRoundTripPreservesAutomationMode() {
        val profile = Profile(
            id = 7,
            name = "Queued profile",
            enterTaskId = 42,
            exitTaskId = 43,
            automationMode = AutomationMode.QUEUED,
            priority = 7,
            gracePeriodSec = 30,
            lifetime = ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 1_700_000_000_000L,
            maxActiveExecutions = 3,
            burstLimit = 12,
            overflowPolicy = ProfileOverflowPolicy.SILENT,
        )

        assertEquals(profile, profile.toEntity().toDomain())
    }

    @Test
    fun profileEntityRoundTripPreservesImportedReviewRequirement() {
        val profile = Profile(
            id = 8,
            name = "Imported",
            enabled = false,
            enterTaskId = 42,
            requiresRiskAcknowledgement = true,
        )

        assertEquals(true, profile.toEntity().toDomain().requiresRiskAcknowledgement)
    }

    @Test
    fun profileEntityRoundTripPreservesNestedContextExpression() {
        val profile = Profile(
            id = 9,
            name = "Nested",
            enterTaskId = 42,
            contexts = listOf(
                ContextSpec(ContextType.STATE),
                ContextSpec(ContextType.EVENT),
            ),
            contextExpression = ContextExpressionNode.group(
                ContextBooleanOperator.OR,
                listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
            ),
        )

        val entity = profile.toEntity()
        assertTrue(entity.contextsJson.trimStart().startsWith("{"))
        assertEquals(profile, entity.toDomain())
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
        val entity = ProfileEntity(
            id = 5,
            name = "Corrupted profile",
            enabled = true,
            enterTaskId = 2,
            exitTaskId = null,
            cooldownSec = 0,
            contextsJson = "{not-json",
        )
        val result = entity.toDomainDecodeResult()

        assertEquals(emptyList<com.opentasker.core.model.ContextSpec>(), result.value.contexts)
        val issue = result.issue
        assertNotNull(issue)
        issue!!
        assertEquals(StorageRecordType.PROFILE, issue.recordType)
        assertEquals(5L, issue.recordId)
        assertEquals("contextsJson", issue.fieldName)
        assertThrows(CorruptStoredRecordException::class.java) { entity.toDomain() }
    }
}
