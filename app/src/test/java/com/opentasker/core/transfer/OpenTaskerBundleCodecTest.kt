package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextBooleanOperator
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.validation.InputValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTaskerBundleCodecTest {
    @Test
    fun schema1GoldenFixtureMigratesDeterministicallyAndRoundTripsAsSchema2() {
        val fixture = checkNotNull(javaClass.classLoader?.getResource("bundles/schema1-golden.json"))
            .readText()

        val migrated = OpenTaskerBundleCodec.decode(fixture)
        val roundTripped = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(migrated))

        assertEquals(OPEN_TASKER_BUNDLE_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(listOf("Parent", "Child"), migrated.tasks.map(Task::name))
        assertEquals("Golden profile", migrated.profiles.single().name)
        assertEquals("COUNT", migrated.variables.single().name)
        assertEquals("Golden scene", migrated.scenes.single().name)
        assertTrue(migrated.metadata.warnings.any { it.startsWith("Migrated bundle schema 1 to 2") })
        assertFalse(migrated.metadata.warnings.any { it.contains("manifest did not match") })
        assertTrue(OpenTaskerBundleCodec.validate(migrated).canImport)
        assertEquals(migrated, roundTripped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsUnsupportedFutureSchemaBeforeDomainDeserialization() {
        OpenTaskerBundleCodec.decode(
            """{"schemaVersion":999,"appVersion":"future","exportedAtEpochMs":0}""",
        )
    }

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
        assertFalse(bundle.metadata.warnings.any { it.contains("manifest did not match") })
    }

    @Test
    fun buildGroupsRequestedPowersAndFlagsDataToExternalChains() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.75",
            exportedAtEpochMs = 123L,
            profiles = listOf(Profile(id = 9, name = "Uploader", enterTaskId = 1)),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Upload local file",
                    actions = listOf(ActionSpec(type = "file.read"), ActionSpec(type = "http.post")),
                ),
            ),
        )

        val request = bundle.metadata.powerRequests.single()
        assertEquals(OPEN_TASKER_BUNDLE_SCHEMA_VERSION, bundle.schemaVersion)
        assertEquals(listOf("Uploader"), request.profileNames)
        assertTrue(AutomationPower.DATA_ACCESS in request.powers)
        assertTrue(AutomationPower.EXTERNAL_TRANSMISSION in request.powers)
        assertEquals(DataToExternalChainRequest("file.read", "http.post"), request.dataToExternalChains.single())
        assertTrue(bundle.metadata.warnings.any { it.contains("Potential data-to-external chain") })
    }

    @Test
    fun buildFlagsDataToExternalChainAcrossReachableSubtask() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.75",
            exportedAtEpochMs = 123L,
            profiles = listOf(Profile(id = 9, name = "Nested uploader", enterTaskId = 1)),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Read parent",
                    actions = listOf(
                        ActionSpec(type = "file.read"),
                        ActionSpec(type = "task.run", args = mapOf("task" to "2")),
                    ),
                ),
                Task(id = 2, name = "Post child", actions = listOf(ActionSpec(type = "http.post"))),
            ),
        )

        assertTrue(
            bundle.metadata.warnings.any {
                it.contains("profile 'Nested uploader'") && it.contains("file.read -> http.post")
            },
        )
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
    fun validateRecomputesForgedVersion2Manifests() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.75",
                exportedAtEpochMs = 123L,
                tasks = listOf(Task(id = 1, name = "Notify", actions = listOf(ActionSpec(type = "notify.show")))),
                metadata = BundleMetadata(
                    capabilityRequirements = emptyList(),
                    powerRequests = emptyList(),
                ),
            ),
        )

        assertTrue(plan.canImport)
        assertEquals("notify.show", plan.capabilityRequirements.single().actionId)
        assertTrue(plan.powerRequests.single().powers.contains(AutomationPower.DEVICE_CONTROL))
        assertTrue(plan.warnings.count { it.contains("manifest did not match") } == 2)
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
    fun validateBlocksTasksThatViolateFieldLimits() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.76",
                exportedAtEpochMs = 123L,
                tasks = listOf(
                    Task(id = 1, name = "Empty", actions = emptyList()),
                    Task(id = 2, name = "x".repeat(InputValidation.MAX_NAME_LENGTH + 1), actions = listOf(ActionSpec(type = "log"))),
                ),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.startsWith("Invalid task 'Empty'") && it.contains("at least one action") })
        assertTrue(plan.warnings.any { it.startsWith("Invalid task") && it.contains("exceeds") })
    }

    @Test
    fun validateBlocksBlankActionTypes() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.76",
                exportedAtEpochMs = 123L,
                tasks = listOf(Task(id = 1, name = "Blank", actions = listOf(ActionSpec(type = "   ")))),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.startsWith("Invalid action") && it.contains("cannot be empty") })
    }

    @Test
    fun validateBlocksInvalidSceneElementConfig() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.79",
                exportedAtEpochMs = 123L,
                scenes = listOf(
                    Scene(
                        id = 1,
                        name = "Broken scene",
                        widthDp = 200,
                        heightDp = 120,
                        elements = listOf(
                            SceneElement(
                                id = 1,
                                type = SceneElementType.IMAGE,
                                xDp = 0,
                                yDp = 0,
                                widthDp = 80,
                                heightDp = 60,
                                config = mapOf("source" to "Image"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.startsWith("Invalid scene 'Broken scene'") })
    }

    @Test
    fun validateBlocksProfileNameAndCooldownFieldLimits() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.76",
                exportedAtEpochMs = 123L,
                tasks = listOf(Task(id = 1, name = "T", actions = listOf(ActionSpec(type = "log")))),
                profiles = listOf(
                    Profile(
                        id = 1,
                        name = "y".repeat(InputValidation.MAX_NAME_LENGTH + 1),
                        enterTaskId = 1,
                        cooldownSec = InputValidation.MAX_COOLDOWN_SEC + 1,
                        contexts = listOf(ContextSpec(ContextType.TIME)),
                    ),
                ),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.startsWith("Invalid profile") && it.contains("name") })
        assertTrue(plan.warnings.any { it.startsWith("Invalid profile") && it.contains("Cooldown") })
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
    fun validateBlocksNormalizedVariableCollisionsAndSecretPayloads() {
        val plan = OpenTaskerBundleCodec.validate(
            OpenTaskerBundle(
                appVersion = "0.2.79",
                exportedAtEpochMs = 123L,
                variables = listOf(
                    Variable(name = "%TOKEN", value = "first", isGlobal = true),
                    Variable(name = "TOKEN", value = "second", isGlobal = true),
                    Variable(name = "API_KEY", value = "must-not-import", isGlobal = true, isSecret = true),
                ),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("duplicate normalized variable names: TOKEN") })
        assertTrue(plan.warnings.any { it.contains("must omit secrets") })
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

    @Test
    fun lifecycleConfigurationRoundTripsAndConsumedOneShotStateIsNotExported() {
        val profile = Profile(
            id = 7,
            name = "Temporary focus",
            enterTaskId = 1,
            contexts = listOf(ContextSpec(ContextType.STATE)),
            priority = 12,
            gracePeriodSec = 45,
            lifetime = ProfileLifetime.UNTIL_DATE,
            expiresAtMs = 1_800_000_000_000L,
            lifetimeConsumed = true,
            maxActiveExecutions = 4,
            burstLimit = 16,
            overflowPolicy = ProfileOverflowPolicy.SILENT,
        )
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.82",
            exportedAtEpochMs = 123L,
            profiles = listOf(profile),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log")))),
        )

        val exportedProfile = bundle.profiles.single()
        assertEquals(profile.priority, exportedProfile.priority)
        assertEquals(profile.gracePeriodSec, exportedProfile.gracePeriodSec)
        assertEquals(profile.lifetime, exportedProfile.lifetime)
        assertEquals(profile.expiresAtMs, exportedProfile.expiresAtMs)
        assertEquals(profile.maxActiveExecutions, exportedProfile.maxActiveExecutions)
        assertEquals(profile.burstLimit, exportedProfile.burstLimit)
        assertEquals(profile.overflowPolicy, exportedProfile.overflowPolicy)
        assertFalse(exportedProfile.lifetimeConsumed)
        assertEquals(bundle, OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle)))
    }

    @Test
    fun jsonRoundTripPreservesNestedContextExpression() {
        val profile = Profile(
            id = 4,
            name = "Nested profile",
            enterTaskId = 1,
            contexts = listOf(ContextSpec(ContextType.STATE), ContextSpec(ContextType.EVENT)),
            contextExpression = ContextExpressionNode.group(
                ContextBooleanOperator.OR,
                listOf(ContextExpressionNode.leaf(0), ContextExpressionNode.leaf(1)),
            ),
        )
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.79",
            exportedAtEpochMs = 123L,
            profiles = listOf(profile),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log")))),
        )

        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))

        assertEquals(profile, decoded.profiles.single())
        assertTrue(OpenTaskerBundleCodec.validate(bundle).canImport)
    }

    @Test
    fun invalidNestedContextExpressionIsRejectedAtImportBoundary() {
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.79",
            exportedAtEpochMs = 123L,
            profiles = listOf(
                Profile(
                    id = 4,
                    name = "Invalid nested profile",
                    enterTaskId = 1,
                    contexts = listOf(ContextSpec(ContextType.STATE)),
                    contextExpression = ContextExpressionNode.leaf(7),
                ),
            ),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log")))),
        )

        val plan = OpenTaskerBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("contextExpression") })
    }

    @Test
    fun projectMembershipRoundTripsAndCrossProjectReferencesAreReported() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.79",
            exportedAtEpochMs = 123L,
            projects = listOf(
                Project(id = 8, name = "Work", position = 1),
                Project(id = 1, name = "Default", position = 0),
            ),
            tasks = listOf(
                Task(id = 10, name = "Work task", projectId = 8, actions = listOf(ActionSpec(type = "log"))),
                Task(id = 11, name = "Default task", projectId = 1, actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "10")))),
            ),
            profiles = listOf(Profile(id = 12, name = "Default profile", enterTaskId = 11, projectId = 1)),
            variables = listOf(
                Variable(name = "%TOKEN", value = "work", isGlobal = true, projectId = 8),
                Variable(name = "%TOKEN", value = "default", isGlobal = true, projectId = 1),
            ),
        )

        val decoded = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))

        assertEquals(bundle, decoded)
        assertTrue(bundle.metadata.warnings.any { it.startsWith("Cross-project reference") })
        assertTrue(OpenTaskerBundleCodec.validate(bundle).canImport)
    }

    @Test
    fun ordinaryBundleBuildOmitsSecretValuesAndRecordsReentryWarning() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.75",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
            variables = listOf(
                Variable("COUNT", "7", isGlobal = true),
                Variable("API_TOKEN", "must-not-export", isGlobal = true, isSecret = true),
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
                    Variable("API_TOKEN", "must-not-export", isGlobal = true, isSecret = true),
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
