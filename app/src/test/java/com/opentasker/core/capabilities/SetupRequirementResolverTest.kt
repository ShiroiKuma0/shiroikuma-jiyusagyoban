package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRequirementResolverTest {
    @Test
    fun emptyWorkspaceHasNoAutomationRequirements() {
        assertTrue(SetupRequirementResolver.resolve(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun enabledProfileCombinesContextAndReachableTaskRequirements() {
        val child = Task(
            id = 2,
            name = "Notify",
            actions = listOf(ActionSpec(type = "brightness.set")),
        )
        val root = Task(
            id = 1,
            name = "Start",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Notify"))),
        )
        val profile = Profile(
            id = 4,
            name = "Work",
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example")),
                ContextSpec(ContextType.EVENT, mapOf("event" to "calendar")),
            ),
            enterTaskId = root.id,
        )

        assertEquals(
            setOf(SetupRequirement.USAGE_ACCESS, SetupRequirement.CALENDAR, SetupRequirement.WRITE_SETTINGS),
            SetupRequirementResolver.resolve(listOf(profile), listOf(root, child)),
        )
    }

    @Test
    fun disabledOrUnacknowledgedProfilesDoNotCreateBlockers() {
        val task = Task(id = 1, name = "Send", actions = listOf(ActionSpec(type = "sms.send")))
        val disabled = Profile(
            id = 1,
            name = "Disabled",
            enabled = false,
            enterTaskId = task.id,
        )
        val unacknowledged = Profile(
            id = 2,
            name = "Review",
            requiresRiskAcknowledgement = true,
            enterTaskId = task.id,
        )

        assertTrue(SetupRequirementResolver.resolve(listOf(disabled, unacknowledged), listOf(task)).isEmpty())
    }

    @Test
    fun contactsLookupAddsContactsPermissionRequirement() {
        val task = Task(id = 1, name = "Find", actions = listOf(ActionSpec(type = "contacts.lookup")))
        val profile = Profile(id = 1, name = "Find contact", enterTaskId = task.id)

        assertEquals(
            setOf(SetupRequirement.CONTACTS),
            SetupRequirementResolver.resolve(listOf(profile), listOf(task)),
        )
    }

    @Test
    fun screenRecordingEventAddsScreenRecordingRequirement() {
        val profile = Profile(
            id = 1,
            name = "Recording",
            contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "screen_recording"))),
            enterTaskId = 1,
        )

        assertEquals(
            setOf(SetupRequirement.SCREEN_RECORDING),
            SetupRequirementResolver.resolve(listOf(profile), emptyList()),
        )
    }

    @Test
    fun physicalStateContextsAddMatchingSensorSetupRequirements() {
        val profile = Profile(
            id = 8,
            name = "Physical state",
            contexts = listOf(
                ContextSpec(ContextType.STATE, mapOf("key" to "activity", "value" to "walking")),
                ContextSpec(ContextType.STATE, mapOf("predicate" to "speed>=10")),
                ContextSpec(ContextType.STATE, mapOf("key" to "roaming", "value" to "true")),
                ContextSpec(ContextType.STATE, mapOf("key" to "phone_call", "value" to "ringing")),
            ),
            enterTaskId = 1,
        )

        assertEquals(
            setOf(
                SetupRequirement.PHYSICAL_ACTIVITY,
                SetupRequirement.FOREGROUND_LOCATION,
                SetupRequirement.PHONE_STATE,
            ),
            SetupRequirementResolver.resolve(listOf(profile), emptyList()),
        )
    }
}
