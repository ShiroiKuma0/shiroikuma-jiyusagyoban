package com.opentasker.core.diff

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.transfer.OpenTaskerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSemanticDiffTest {
    @Test
    fun taskDiffReportsActionAddRemoveAndMasksSensitiveArguments() {
        val before = Task(
            id = 10,
            name = "Morning",
            actions = listOf(ActionSpec(id = 3, type = "http.post", args = mapOf("url" to "https://example.test", "password" to "old-secret"))),
        )
        val after = Task(
            id = 99,
            name = "Morning",
            actions = listOf(
                ActionSpec(id = 44, type = "http.post", args = mapOf("url" to "https://example.test", "password" to "new-secret")),
                ActionSpec(type = "notify.show", args = mapOf("title" to "Done")),
            ),
        )

        val diff = AutomationSemanticDiff.compareTask(before, after)

        assertNotNull(diff)
        assertTrue(diff!!.changes.any { it.path == "Action 2" && it.kind == SemanticDiffKind.ADDED })
        val secret = diff.changes.first { it.path == "Action 1 / Argument password" }
        assertEquals(SemanticDiffKind.CHANGED, secret.kind)
        assertEquals("<redacted>", secret.before)
        assertEquals("<redacted>", secret.after)
        assertTrue(diff.flowNodeKeys.contains("task:10"))
        assertTrue(diff.flowNodeKeys.contains("task:10:action:0"))
    }

    @Test
    fun profileDiffReportsContextRemovalAndIgnoresPersistedIds() {
        val before = Profile(
            id = 4,
            name = "At home",
            enabled = true,
            contexts = listOf(
                ContextSpec(ContextType.STATE, mapOf("state" to "charging")),
                ContextSpec(ContextType.EVENT, mapOf("event" to "notification")),
            ),
            enterTaskId = 11,
        )
        val after = before.copy(
            id = 88,
            contexts = before.contexts.take(1),
            enterTaskId = 111,
        )

        val diff = AutomationSemanticDiff.compareProfile(
            before,
            after,
            beforeTaskNames = mapOf(11L to "Start"),
            afterTaskNames = mapOf(111L to "Start"),
        )

        assertNotNull(diff)
        assertTrue(diff!!.changes.any { it.path == "Context 2" && it.kind == SemanticDiffKind.REMOVED })
        assertFalse(diff.changes.any { it.path == "Enter task" })
        assertTrue(diff.flowNodeKeys.contains("profile:4"))
        assertTrue(diff.flowNodeKeys.contains("profile:4:context:1"))
    }

    @Test
    fun unchangedSceneWithFreshStorageIdsHasNoDiff() {
        val before = Scene(
            id = 1,
            name = "Controls",
            widthDp = 320,
            heightDp = 240,
            elements = listOf(SceneElement(id = 4, type = SceneElementType.BUTTON, xDp = 1, yDp = 2, widthDp = 80, heightDp = 40)),
        )
        val after = before.copy(id = 99, elements = listOf(before.elements.single().copy(id = 500)))

        assertNull(AutomationSemanticDiff.compareScene(before, after))
    }

    @Test
    fun bundleDiffNormalizesRemappedTaskReferencesAndMasksVariables() {
        val existingTask = Task(id = 101, name = "Start", actions = listOf(ActionSpec(type = "notify.show")))
        val importedTask = Task(id = 1, name = "Start", actions = listOf(ActionSpec(type = "notify.show")))
        val existingProfile = Profile(id = 201, name = "Home", enabled = false, requiresRiskAcknowledgement = true, enterTaskId = 101)
        val importedProfile = Profile(id = 2, name = "Home", enabled = true, enterTaskId = 1)
        val existingVariable = Variable(name = "api_token", value = "old")
        val importedVariable = Variable(name = "api_token", value = "new")
        val document = AutomationSemanticDiff.compareBundle(
            bundle = OpenTaskerBundle(
                appVersion = "test",
                exportedAtEpochMs = 1,
                tasks = listOf(importedTask),
                profiles = listOf(importedProfile),
                variables = listOf(importedVariable),
            ),
            existingTasks = listOf(existingTask),
            existingProfiles = listOf(existingProfile),
            existingVariables = listOf(existingVariable),
            existingScenes = emptyList(),
        )

        assertEquals(1, document.entries.size)
        val variableChange = document.entries.single().changes.single()
        assertEquals(SemanticDiffEntity.VARIABLE, document.entries.single().entity)
        assertEquals("<redacted>", variableChange.before)
        assertEquals("<redacted>", variableChange.after)
    }

    @Test
    fun bundleDiffUsesResolvedProjectIdsWhenMatchingAReimport() {
        val existingTask = Task(
            id = 101,
            projectId = 8,
            name = "Start",
            actions = listOf(ActionSpec(type = "notify.show")),
        )
        val importedTask = Task(
            id = 1,
            projectId = 2,
            name = "Start",
            actions = listOf(ActionSpec(type = "notify.show")),
        )
        val existingProfile = Profile(
            id = 201,
            projectId = 8,
            name = "Home",
            enabled = false,
            requiresRiskAcknowledgement = true,
            enterTaskId = 101,
        )
        val importedProfile = Profile(
            id = 2,
            projectId = 2,
            name = "Home",
            enabled = true,
            enterTaskId = 1,
        )

        val document = AutomationSemanticDiff.compareBundle(
            bundle = OpenTaskerBundle(
                appVersion = "test",
                exportedAtEpochMs = 1,
                tasks = listOf(importedTask),
                profiles = listOf(importedProfile),
            ),
            existingTasks = listOf(existingTask),
            existingProfiles = listOf(existingProfile),
            existingVariables = emptyList(),
            existingScenes = emptyList(),
            projectIdMap = mapOf(2L to 8L),
        )

        assertTrue(document.isEmpty)
    }
}
