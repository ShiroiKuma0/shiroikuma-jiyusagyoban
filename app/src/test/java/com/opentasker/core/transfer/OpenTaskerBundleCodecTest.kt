package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                Variable(name = "%Z", value = "2", projectId = 0),
                Variable(name = "%A", value = "1", projectId = 0),
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
        assertEquals(CapabilityLevel.RequiresSetup, requirements.getValue("reboot").level)
        assertFalse(requirements.containsKey("log"))
        assertFalse(bundle.metadata.warnings.any { it.contains("manifest did not match") })
    }

    @Test
    fun validateBlocksUnknownUnclassifiedActions() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "future",
                exportedAtEpochMs = 123L,
                tasks = listOf(Task(id = 1, name = "Unknown", actions = listOf(ActionSpec(type = "future.action")))),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("unknown unclassified actions") })
    }

    @Test
    fun validateReportsLossyReferencesAndUnsupportedActions() {
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "app.kill")))),
            profiles = listOf(Profile(id = 1, name = "Broken", enterTaskId = 99, exitTaskId = 42)),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertTrue(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("unsupported actions") })
        assertTrue(plan.lossyWarnings.any { it.contains("points to a task that isn't part of this import") })
        assertTrue(plan.lossyWarnings.any { it.contains("has an exit task that isn't part of this import") })
    }

    @Test
    fun validateBlocksDuplicateTaskNamesAndVariableNames() {
        // Names are the identity now — two same-named tasks can't both be a link target.
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.73",
            exportedAtEpochMs = 123L,
            tasks = listOf(
                Task(id = 7, name = "Same"),
                Task(id = 8, name = "Same"),
            ),
            variables = listOf(
                Variable(name = "%TOKEN", value = "first", projectId = 0),
                Variable(name = "%TOKEN", value = "second", projectId = 0),
            ),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("duplicate task names: Same") })
        assertTrue(plan.warnings.any { it.contains("duplicate variable names: %TOKEN") })
    }

    @Test
    fun validateAllowsSameNameVariablesInDifferentScopes() {
        // A super-global %DT_Ampmn and a project-scoped one are DISTINCT — the app's own full export has
        // exactly this pairing (47 of them), so it must round-trip, not be rejected as a "duplicate name".
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.73",
            exportedAtEpochMs = 123L,
            variables = listOf(
                Variable(name = "%DT_Ampmn", value = "午前", projectId = 0),
                Variable(name = "%DT_Ampmn", value = "午後", projectId = 15),
            ),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertTrue(plan.canImport)
        assertFalse(plan.warnings.any { it.contains("duplicate variable names") })
    }

    /** A rich bundle whose refs are all id-based on the domain side (name fields blank, a numeric scene
     *  ref in an action arg) — the hardest case for the exporter to fully name-ify. */
    private fun richBundle() = OpenTaskerBundle(
        appVersion = "test",
        exportedAtEpochMs = 0L,
        projects = listOf(Project(id = 10, name = "時計")),
        tasks = listOf(
            Task(
                id = 1, name = "Driver", projectId = 10,
                actions = listOf(ActionSpec(type = "scene.show", args = mapOf("scene" to "5"))), // numeric scene id
            ),
        ),
        profiles = listOf(
            Profile(id = 2, name = "Trigger", enterTaskId = 1, projectId = 10, enterTaskName = "", contexts = listOf(ContextSpec(ContextType.EVENT))),
        ),
        scenes = listOf(
            Scene(
                id = 5, name = "Panel", widthDp = 10, heightDp = 10, projectId = 10,
                elements = listOf(SceneElement(id = 1, type = SceneElementType.TEXT, xDp = 0, yDp = 0, widthDp = 0, heightDp = 0, tapTaskId = 1)), // link by id, name blank
            ),
        ),
        variables = listOf(Variable(name = "%Speed", value = "1", projectId = 10)),
        groups = listOf(ItemGroupSpec(id = 100, tab = "tasks", projectId = 10, name = "時計群")),
        itemMeta = listOf(ItemMetaSpec(tab = "tasks", itemKey = "1", note = "the driver", groupId = 100)),
    )

    @Test
    fun exportIsIdFreeAndNameBased() {
        val json = OpenTaskerBundleCodec.encode(richBundle())

        // Not a single numeric-id key survives.
        for (idKey in listOf("\"id\":", "\"projectId\":", "\"enterTaskId\":", "\"exitTaskId\":",
            "\"tapTaskId\":", "\"longPressTaskId\":", "\"itemKey\":", "\"groupId\":", "\"parentGroupId\":")) {
            assertFalse("leaked $idKey in:\n$json", json.contains(idKey))
        }
        // References are carried by name instead, and the numeric scene ref in the arg was rewritten.
        assertTrue(json.contains("\"schemaVersion\": 5"))
        assertTrue(json.contains("\"projectName\": \"時計\""))
        assertTrue(json.contains("\"enterTaskName\": \"Driver\""))   // backfilled from the id
        assertTrue(json.contains("\"tapTaskName\": \"Driver\""))     // backfilled from the id
        assertTrue(json.contains("\"itemName\": \"Driver\""))
        assertTrue(json.contains("\"scene\": \"Panel\""))            // arg "5" → scene name
        assertFalse(json.contains("\"scene\": \"5\""))
    }

    @Test
    fun roundTripPreservesRelationshipsByName() {
        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(richBundle()))

        val project = decoded.projects.single()
        assertEquals("時計", project.name)
        val task = decoded.tasks.single { it.name == "Driver" }
        val scene = decoded.scenes.single { it.name == "Panel" }
        val profile = decoded.profiles.single { it.name == "Trigger" }

        // Task → project resolved by name.
        assertEquals(project.id, task.projectId)
        // Profile → task resolved by name (and the name field is populated).
        assertEquals("Driver", profile.enterTaskName)
        assertEquals(task.id, profile.enterTaskId)
        // Scene element → task resolved by name.
        assertEquals("Driver", scene.elements.single().tapTaskName)
        assertEquals(task.id, scene.elements.single().tapTaskId)
        // Action arg carries the scene NAME (not a numeric id).
        assertEquals("Panel", task.actions.single().args["scene"])
        // Variable scope → project by name.
        assertEquals(project.id, decoded.variables.single().projectId)
        // Group + note round-trip: the note attaches to the driver task and sits in the group.
        val group = decoded.groups.single { it.name == "時計群" }
        assertEquals(project.id, group.projectId)
        val meta = decoded.itemMeta.single { it.tab == "tasks" }
        assertEquals(task.id.toString(), meta.itemKey)
        assertEquals(group.id, meta.groupId)
    }

    @Test
    fun decodeRejectsOldIdBearingFormat() {
        val old = """{"schemaVersion":4,"appVersion":"x","exportedAtEpochMs":0,"tasks":[{"id":1,"name":"T"}]}"""
        val error = runCatching { OpenTaskerBundleCodec.decode(old) }.exceptionOrNull()
        assertNotNull("expected old format to be rejected", error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("older format"))
    }

    @Test
    fun ordinaryBundleBuildOmitsSecretValuesAndRecordsReentryWarning() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.75",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
            variables = listOf(
                Variable("COUNT", "7"),
                Variable("API_TOKEN", "must-not-export", isSecret = true),
            ),
        )

        val encoded = OpenTaskerBundleCodec.encode(bundle)
        assertEquals(listOf("COUNT"), bundle.variables.map { it.name })
        assertFalse(encoded.contains("must-not-export"))
        assertTrue(bundle.metadata.warnings.any { it.contains("must be re-entered") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun codecRejectsDirectSecretSerialization() {
        OpenTaskerBundleCodec.encode(
            OpenTaskerBundle(
                appVersion = "0.2.75",
                exportedAtEpochMs = 123L,
                variables = listOf(
                    Variable("API_TOKEN", "must-not-export", isSecret = true),
                ),
            ),
        )
    }
    /**
     * A user who pasted a secret's plaintext into an ordinary-looking argument used to get it
     * exported in the clear: the JSON export built its redaction context from secret *names* only,
     * so nothing could match the literal value.
     */
    @Test
    fun jsonExportRedactsALiteralCopyOfASecretValue() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Publish",
                    actions = listOf(
                        ActionSpec(type = "log", args = mapOf("message" to "token is sk-live-abc123")),
                    ),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val withNamesOnly = OpenTaskerBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("ApiToken"),
        )
        val withValues = OpenTaskerBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("ApiToken"),
            secretVariableValues = setOf("sk-live-abc123"),
        )

        val exported = withValues.tasks.single().actions.single().args.getValue("message")
        assertFalse("the secret's plaintext must not survive export", exported.contains("sk-live-abc123"))
        assertTrue(
            "redaction must be visible rather than silent",
            exported != withNamesOnly.tasks.single().actions.single().args.getValue("message"),
        )
    }

}
