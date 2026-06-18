package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTaskerBundleCodecTest {
    @Test
    fun buildSortsTopLevelCollectionsForStableDiffs() {
        val firstTask = Task(id = 2, name = "B Task", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "b"))))
        val secondTask = Task(id = 1, name = "A Task", actions = listOf(ActionSpec(type = "notify.show")))

        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            profiles = listOf(
                Profile(id = 2, name = "Z Profile", enterTaskId = 2, contexts = listOf(ContextSpec(ContextType.TIME))),
                Profile(id = 1, name = "A Profile", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.STATE))),
            ),
            tasks = listOf(firstTask, secondTask),
            variables = listOf(
                Variable(name = "%Z", value = "2", isGlobal = true),
                Variable(name = "%A", value = "1", isGlobal = true),
            ),
        )

        assertEquals(listOf("A Task", "B Task"), bundle.tasks.map { it.name })
        assertEquals(listOf("A Profile", "Z Profile"), bundle.profiles.map { it.name })
        assertEquals(listOf("%A", "%Z"), bundle.variables.map { it.name })
    }

    @Test
    fun buildRecordsCapabilityRequirements() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Restricted",
                    actions = listOf(
                        ActionSpec(type = "notify.show"),
                        ActionSpec(type = "reboot"),
                        ActionSpec(type = "log"),
                    ),
                )
            ),
        )

        val requirements = bundle.metadata.capabilityRequirements.associateBy { it.actionId }
        assertEquals(CapabilityLevel.RequiresSetup, requirements.getValue("notify.show").level)
        assertEquals(CapabilityLevel.Unsupported, requirements.getValue("reboot").level)
        assertFalse(requirements.containsKey("log"))
    }

    @Test
    fun validateReportsLossyReferencesAndUnsupportedActions() {
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "reboot")))),
            profiles = listOf(Profile(id = 1, name = "Broken", enterTaskId = 99, exitTaskId = 42)),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertTrue(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("unsupported actions") })
        assertTrue(plan.lossyWarnings.any { it.contains("missing enter task") })
        assertTrue(plan.lossyWarnings.any { it.contains("missing exit task") })
    }

    @Test
    fun validateBlocksAmbiguousDuplicateIdsAndVariableNames() {
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.73",
            exportedAtEpochMs = 123L,
            tasks = listOf(
                Task(id = 7, name = "First"),
                Task(id = 7, name = "Second"),
            ),
            variables = listOf(
                Variable(name = "%TOKEN", value = "first", isGlobal = true),
                Variable(name = "%TOKEN", value = "second", isGlobal = true),
            ),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("duplicate task ids: 7") })
        assertTrue(plan.warnings.any { it.contains("duplicate variable names: %TOKEN") })
    }

    @Test
    fun jsonRoundTripPreservesBundle() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            profiles = listOf(Profile(id = 1, name = "Profile", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.EVENT)))),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "hello"))))),
        )

        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))

        assertEquals(bundle, decoded)
    }
}
