package com.sysadmindoc.ruledeck.core.transfer

import com.sysadmindoc.ruledeck.core.capabilities.CapabilityLevel
import com.sysadmindoc.ruledeck.core.capabilities.AutomationPower
import com.sysadmindoc.ruledeck.core.diagnostics.ExportRedactionPolicy
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.AutomationInvariant
import com.sysadmindoc.ruledeck.core.model.ContextBooleanOperator
import com.sysadmindoc.ruledeck.core.model.ContextExpressionNode
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.InvariantStatePredicate
import com.sysadmindoc.ruledeck.core.model.ProfileLifetime
import com.sysadmindoc.ruledeck.core.model.ProfileOverflowPolicy
import com.sysadmindoc.ruledeck.core.model.Project
import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.SceneElement
import com.sysadmindoc.ruledeck.core.model.SceneElementType
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.model.Variable
import com.sysadmindoc.ruledeck.core.templates.BlueprintInput
import com.sysadmindoc.ruledeck.core.templates.BlueprintSelectorKind
import com.sysadmindoc.ruledeck.core.templates.ProfileTemplateCatalog
import com.sysadmindoc.ruledeck.core.validation.InputValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDeckBundleCodecTest {
    @Test
    fun schema1GoldenFixtureMigratesDeterministicallyAndRoundTripsAsSchema2() {
        val fixture = checkNotNull(javaClass.classLoader?.getResource("bundles/schema1-golden.json"))
            .readText()

        val migrated = RuleDeckBundleCodec.decode(fixture)
        val roundTripped = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(migrated))

        assertEquals(RULEDECK_BUNDLE_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(listOf("Parent", "Child"), migrated.tasks.map(Task::name))
        assertEquals("Golden profile", migrated.profiles.single().name)
        assertEquals("COUNT", migrated.variables.single().name)
        assertEquals("Golden scene", migrated.scenes.single().name)
        assertTrue(migrated.metadata.warnings.any { it.startsWith("Migrated bundle schema 1 to 2") })
        assertFalse(migrated.metadata.warnings.any { it.contains("manifest did not match") })
        assertTrue(RuleDeckBundleCodec.validate(migrated).canImport)
        assertEquals(migrated, roundTripped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsUnsupportedFutureSchemaBeforeDomainDeserialization() {
        RuleDeckBundleCodec.decode(
            """{"schemaVersion":999,"appVersion":"future","exportedAtEpochMs":0}""",
        )
    }

    @Test
    fun buildSortsTopLevelCollectionsForStableDiffs() {
        val firstTask = Task(id = 2, name = "B Task", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "b"))))
        val secondTask = Task(id = 1, name = "A Task", actions = listOf(ActionSpec(type = "notify.show")))

        val bundle = RuleDeckBundleCodec.build(
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
        val bundle = RuleDeckBundleCodec.build(
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
    fun buildGroupsRequestedPowersAndFlagsDataToExternalChains() {
        val bundle = RuleDeckBundleCodec.build(
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
        assertEquals(RULEDECK_BUNDLE_SCHEMA_VERSION, bundle.schemaVersion)
        assertEquals(listOf("Uploader"), request.profileNames)
        assertTrue(AutomationPower.DATA_ACCESS in request.powers)
        assertTrue(AutomationPower.EXTERNAL_TRANSMISSION in request.powers)
        assertEquals(DataToExternalChainRequest("file.read", "http.post"), request.dataToExternalChains.single())
        assertTrue(bundle.metadata.warnings.any { it.contains("Potential data-to-external chain") })
    }

    @Test
    fun buildFlagsDataToExternalChainAcrossReachableSubtask() {
        val bundle = RuleDeckBundleCodec.build(
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
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val bundle = RuleDeckBundle(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "app.kill")))),
            profiles = listOf(Profile(id = 1, name = "Broken", enterTaskId = 99, exitTaskId = 42)),
        )

        val plan = RuleDeckBundleCodec.validate(bundle)

        assertTrue(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("unsupported actions") })
        assertTrue(plan.lossyWarnings.any { it.contains("missing enter task") })
        assertTrue(plan.lossyWarnings.any { it.contains("missing exit task") })
    }

    @Test
    fun validateBlocksTasksThatViolateFieldLimits() {
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val bundle = RuleDeckBundle(
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

        val plan = RuleDeckBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("duplicate task ids: 7") })
        assertTrue(plan.warnings.any { it.contains("duplicate variable names: %TOKEN") })
    }

    @Test
    fun validateBlocksNormalizedVariableCollisionsAndSecretPayloads() {
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
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
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.2.13",
            exportedAtEpochMs = 123L,
            profiles = listOf(Profile(id = 1, name = "Profile", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.EVENT)))),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "hello"))))),
        )

        val decoded = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle))

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
        val bundle = RuleDeckBundleCodec.build(
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
        assertEquals(bundle, RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle)))
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
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.2.79",
            exportedAtEpochMs = 123L,
            profiles = listOf(profile),
            tasks = listOf(Task(id = 1, name = "Task", actions = listOf(ActionSpec(type = "log")))),
        )

        val decoded = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle))

        assertEquals(profile, decoded.profiles.single())
        assertTrue(RuleDeckBundleCodec.validate(bundle).canImport)
    }

    @Test
    fun invalidNestedContextExpressionIsRejectedAtImportBoundary() {
        val bundle = RuleDeckBundle(
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

        val plan = RuleDeckBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("contextExpression") })
    }

    /** The check read a call to a task named "2025" as a call to a missing id 2025. */
    @Test
    fun aCrossProjectCallToATaskNamedWithANumberIsReported() {
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.2.79",
            exportedAtEpochMs = 123L,
            projects = listOf(
                Project(id = 8, name = "Work", position = 1),
                Project(id = 1, name = "Default", position = 0),
            ),
            tasks = listOf(
                Task(id = 10, name = "2025", projectId = 8, actions = listOf(ActionSpec(type = "log"))),
                Task(id = 11, name = "Default task", projectId = 1, actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "2025")))),
            ),
            profiles = emptyList(),
        )

        assertTrue(RuleDeckBundleCodec.validate(bundle).warnings.any { it.startsWith("Cross-project reference") })
    }

    @Test
    fun projectMembershipRoundTripsAndCrossProjectReferencesAreReported() {
        val bundle = RuleDeckBundleCodec.build(
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

        val decoded = RuleDeckBundleCodec.decode(RuleDeckBundleCodec.encode(bundle))

        assertEquals(bundle, decoded)
        assertTrue(bundle.metadata.warnings.any { it.startsWith("Cross-project reference") })
        assertTrue(RuleDeckBundleCodec.validate(bundle).canImport)
    }

    @Test
    fun ordinaryBundleBuildOmitsSecretValuesAndRecordsReentryWarning() {
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.2.75",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
            variables = listOf(
                Variable("COUNT", "7", isGlobal = true),
                Variable("API_TOKEN", "must-not-export", isGlobal = true, isSecret = true),
            ),
        )

        val encoded = RuleDeckBundleCodec.encode(bundle)
        assertEquals(listOf("COUNT"), bundle.variables.map { it.name })
        assertFalse(encoded.contains("must-not-export"))
        assertTrue(bundle.metadata.warnings.contains("1 secret variable was omitted and must be re-entered after import."))
    }

    @Test
    fun blueprintBlockRoundTripsAndIsOmittedFromEmptyLegacyExports() {
        val blueprint = ProfileTemplateCatalog.get("app-usage-reminder")!!.copy(version = 2)
        val withBlueprint = RuleDeckBundleCodec.build(
            appVersion = "0.2.84",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
            blueprints = listOf(blueprint),
        )
        val encoded = RuleDeckBundleCodec.encode(withBlueprint)
        val emptyEncoded = RuleDeckBundleCodec.encode(
            RuleDeckBundle(appVersion = "0.2.84", exportedAtEpochMs = 123L),
        )

        assertTrue(encoded.contains("\"blueprints\""))
        assertEquals(withBlueprint, RuleDeckBundleCodec.decode(encoded))
        assertFalse(emptyEncoded.contains("\"blueprints\""))
        assertTrue(RuleDeckBundleCodec.validate(withBlueprint).canImport)
    }

    @Test
    fun invariantBlockRoundTripsAndIsOmittedFromEmptyLegacyExports() {
        val invariant = AutomationInvariant(
            id = 7,
            name = "Keep display bright",
            guard = InvariantStatePredicate(key = "charging", value = "true"),
            forbiddenWriteKey = "brightness",
        )
        val withInvariant = RuleDeckBundleCodec.build(
            appVersion = "0.2.84",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
            invariants = listOf(invariant),
        )
        val encoded = RuleDeckBundleCodec.encode(withInvariant)
        val emptyEncoded = RuleDeckBundleCodec.encode(
            RuleDeckBundle(appVersion = "0.2.84", exportedAtEpochMs = 123L),
        )

        assertTrue(encoded.contains("\"invariants\""))
        assertEquals(withInvariant, RuleDeckBundleCodec.decode(encoded))
        assertFalse(emptyEncoded.contains("\"invariants\""))
        assertTrue(RuleDeckBundleCodec.validate(withInvariant).canImport)
    }

    @Test
    fun invalidBlueprintDefaultsAreRejectedAtTheImportBoundary() {
        val invalid = ProfileTemplateCatalog.get("app-usage-reminder")!!.copy(
            inputs = listOf(
                BlueprintInput(
                    key = "package",
                    label = "App package",
                    defaultValue = "not a package",
                    selector = BlueprintSelectorKind.APP,
                ),
            ),
        )
        val bundle = RuleDeckBundle(
            appVersion = "0.2.84",
            exportedAtEpochMs = 123L,
            blueprints = listOf(invalid),
        )

        val plan = RuleDeckBundleCodec.validate(bundle)

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.startsWith("Invalid blueprint") })
    }

    @Test
    fun blueprintInputKeysCannotCollideWithSectionHeaderKeys() {
        val template = ProfileTemplateCatalog.get("app-usage-reminder")!!
        val colliding = template.copy(
            inputs = listOf(
                template.inputs.first().copy(key = "${BLUEPRINT_SECTION_KEY_PREFIX}General"),
            ),
        )
        val plan = RuleDeckBundleCodec.validate(
            RuleDeckBundle(
                appVersion = "0.2.88",
                exportedAtEpochMs = 123L,
                blueprints = listOf(colliding),
            ),
        )

        assertFalse(plan.canImport)
        assertTrue(plan.warnings.any { it.contains("collides with the section-header key namespace") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun codecRejectsDirectSecretSerialization() {
        RuleDeckBundleCodec.encode(
            RuleDeckBundle(
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
        val bundle = RuleDeckBundleCodec.build(
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

        val withNamesOnly = RuleDeckBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("ApiToken"),
        )
        val withValues = RuleDeckBundleCodec.sanitizeForExport(
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

    /**
     * The guard and the label are user text too. Only `args` was sanitized, so a run-only-if that
     * compared a secret against its literal value carried that value into the bundle, the paste
     * text, and a shared profile.
     */
    @Test
    fun jsonExportRedactsASecretValueInAGuardAndALabel() {
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Unlock",
                    actions = listOf(
                        ActionSpec(
                            type = "log",
                            label = "check sk-live-abc123",
                            args = mapOf("message" to "ok"),
                            condition = "%Pin == sk-live-abc123",
                        ),
                    ),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val sanitized = RuleDeckBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("Pin"),
            secretVariableValues = setOf("sk-live-abc123"),
        )

        val action = sanitized.tasks.single().actions.single()
        assertFalse(
            "the guard must not carry the secret's plaintext",
            action.condition.orEmpty().contains("sk-live-abc123"),
        )
        assertFalse(
            "the label must not carry the secret's plaintext",
            action.label.orEmpty().contains("sk-live-abc123"),
        )
        assertEquals(
            "a redacted guard must not stay a comparison; see the != case below",
            ExportRedactionPolicy.REDACTED,
            action.condition,
        )
        assertTrue(
            "a redacted field must raise the export warning",
            sanitized.metadata.warnings.contains(ExportRedactionPolicy.SENSITIVE_ACTION_WARNING),
        )
    }

    /**
     * A redacted guard has to be false, not merely different.
     *
     * Substituting the secret in place kept the comparison, so `%Pin != 4321` became
     * `%Pin != [REDACTED]`, which is true for every value %Pin can realistically hold: an action
     * that was guarded before export would have run unguarded after import.
     */
    @Test
    fun aRedactedNotEqualsGuardCannotTurnIntoAnAlwaysTrueCondition() {
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Unlock",
                    actions = listOf(
                        ActionSpec(type = "log", args = mapOf("message" to "ok"), condition = "%Pin != 4321"),
                    ),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val sanitized = RuleDeckBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("Pin"),
            secretVariableValues = setOf("4321"),
        )

        val guard = sanitized.tasks.single().actions.single().condition
        assertEquals(ExportRedactionPolicy.REDACTED, guard)
        // The engine reads an unparseable condition as false, so the action is skipped rather
        // than running with no guard at all.
        assertFalse(
            "a redacted guard must not evaluate true",
            VariableStore().evaluateCondition(guard.orEmpty()),
        )
    }

    /** A user with no secrets must not have ordinary labels rewritten by the credential patterns. */
    @Test
    fun exportLeavesAnOrdinaryLabelAloneWhenNoSecretIsConfigured() {
        val label = "Set config key=abc123 via http://192.168.1.5/api"
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Publish",
                    actions = listOf(ActionSpec(type = "log", label = label, args = mapOf("message" to "ok"))),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val sanitized = RuleDeckBundleCodec.sanitizeForExport(bundle)

        assertEquals(label, sanitized.tasks.single().actions.single().label)
        assertFalse(
            "nothing was redacted, so the export must not warn about secrets",
            sanitized.metadata.warnings.contains(ExportRedactionPolicy.SENSITIVE_ACTION_WARNING),
        )
    }

    /** A guard that only names a secret leaks nothing, and redacting it would break the profile. */
    @Test
    fun jsonExportKeepsAGuardThatOnlyNamesASecret() {
        val bundle = RuleDeckBundleCodec.build(
            appVersion = "0.0.0",
            exportedAtEpochMs = 0L,
            profiles = emptyList(),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Unlock",
                    actions = listOf(
                        ActionSpec(type = "log", args = mapOf("message" to "ok"), condition = "%Pin == is_set"),
                    ),
                ),
            ),
            variables = emptyList(),
            scenes = emptyList(),
            projects = emptyList(),
        )

        val sanitized = RuleDeckBundleCodec.sanitizeForExport(
            bundle,
            secretVariableNames = setOf("Pin"),
            secretVariableValues = setOf("sk-live-abc123"),
        )

        assertEquals("%Pin == is_set", sanitized.tasks.single().actions.single().condition)
    }
}
