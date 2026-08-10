package com.opentasker.core.capabilities

import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSensitivityTest {
    @Test
    fun everyMetadataActionHasAnExplicitSensitivityClassification() {
        registerActionMetadata()

        assertEquals(
            ActionMetadataRegistry.all().mapTo(sortedSetOf()) { it.id },
            AutomationSensitivityRegistry.classifiedActionIds().toSortedSet(),
        )
    }

    @Test
    fun unknownActionsReceiveEveryPowerAndFailClosed() {
        val classification = AutomationSensitivityRegistry.classify("future.unreviewed")

        assertFalse(classification.known)
        assertEquals(AutomationPower.entries.toSet(), classification.powers)
        assertEquals(CapabilityLevel.Unsupported, ActionCapabilityRegistry.get("future.unreviewed").level)
    }

    @Test
    fun taskSummaryFlagsDataToExternalSequence() {
        val summary = AutomationSensitivityRegistry.summarize(
            Task(
                id = 1,
                name = "Exfiltration review",
                actions = listOf(
                    ActionSpec(type = "file.read"),
                    ActionSpec(type = "text.substring"),
                    ActionSpec(type = "http.post"),
                ),
            ),
        )

        assertTrue(AutomationPower.DATA_ACCESS in summary.powers)
        assertTrue(AutomationPower.EXTERNAL_TRANSMISSION in summary.powers)
        assertEquals(DataToExternalChain("file.read", "http.post"), summary.dataToExternalChains.single())
    }

    @Test
    fun importedProfileReviewIncludesReachableSubtasksAndBlocksUnsupportedActions() {
        val child = Task(
            id = 2,
            name = "Child",
            actions = listOf(ActionSpec(type = "tasker.unsupported")),
        )
        val parent = Task(
            id = 1,
            name = "Parent",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "2"))),
        )
        val profile = Profile(
            name = "Imported",
            enabled = false,
            enterTaskId = 1,
            requiresRiskAcknowledgement = true,
        )

        val review = ImportedProfileEnablePolicy.review(profile, listOf(parent, child))

        assertTrue(review.requiresAcknowledgement)
        assertFalse(review.canAcknowledge)
        assertEquals(setOf("tasker.unsupported"), review.unsupportedActionIds)
    }

    @Test
    fun reviewedLocalProfileCanBeAcknowledgedButMissingTaskCannot() {
        val task = Task(id = 1, name = "Local", actions = listOf(ActionSpec(type = "log")))
        val profile = Profile(
            name = "Imported",
            enabled = false,
            enterTaskId = 1,
            requiresRiskAcknowledgement = true,
        )

        assertTrue(ImportedProfileEnablePolicy.review(profile, listOf(task)).canAcknowledge)
        assertFalse(ImportedProfileEnablePolicy.review(profile.copy(enterTaskId = 99), listOf(task)).canAcknowledge)
    }

    @Test
    fun importedProfileReviewBlocksEqualPriorityWriterConflictWithPeers() {
        val importedTask = Task(
            id = 1,
            name = "Imported dim",
            priority = 5,
            actions = listOf(ActionSpec(type = "brightness.set")),
        )
        val peerTask = Task(
            id = 2,
            name = "Existing dim",
            priority = 5,
            actions = listOf(ActionSpec(type = "brightness.set")),
        )
        val imported = Profile(
            id = 10,
            name = "Imported",
            enabled = false,
            enterTaskId = importedTask.id,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
            requiresRiskAcknowledgement = true,
        )
        val peer = Profile(
            id = 11,
            name = "Existing",
            enabled = true,
            enterTaskId = peerTask.id,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.reader"))),
        )

        val review = ImportedProfileEnablePolicy.review(imported, listOf(importedTask, peerTask), listOf(peer))

        assertFalse(review.canAcknowledge)
        assertTrue(review.lintFindings.any { it.code == AutomationLintCode.PRIORITY_CONFLICT })
    }
}
