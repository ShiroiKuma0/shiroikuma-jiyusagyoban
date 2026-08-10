package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionFieldOption
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionNumberRule
import com.opentasker.core.actions.FieldType
import com.opentasker.core.apps.InstalledApp
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.VariableConflictAction
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.core.transfer.VariableImportConflict
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CriticalFlowComposeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun setupOnboardingShowsThemeAndBackupEntryPoints() {
        composeTestRule.setContent {
            TestTheme {
                PermissionOnboardingScreen(
                    contentPadding = PaddingValues(0.dp),
                    onMessage = {},
                    backupState = BackupSetupState(
                        busy = false,
                        latestBackupName = null,
                        pendingRestore = false,
                    ),
                    onCreateBackup = {},
                    onExportBackup = {},
                    onImportBackup = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Setup checklist").assertIsDisplayed()
        composeTestRule.onNodeWithText("Theme").assertIsDisplayed()
        composeTestRule.onNodeWithText("Backup and restore").assertIsDisplayed()
        composeTestRule.onNodeWithText("Locale execution grants").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Locale execution grants are issued.").assertIsDisplayed()
        composeTestRule.onNodeWithText("System").assertIsDisplayed()
    }

    @Test
    fun taskEditorRequiresValidTaskName() {
        var savedName: String? = null
        composeTestRule.setContent {
            TestTheme {
                TaskEditorDialog(
                    task = null,
                    onDismiss = {},
                    onSave = { name, _, mode -> savedName = "$name:$mode" },
                )
            }
        }

        composeTestRule.onNodeWithText("Create Task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("Morning focus")
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("Morning focus:${CollisionMode.ABORT_NEW}", savedName)
    }

    @Test
    fun taskEditorRestoresDraftAcrossSavedInstanceState() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            TestTheme {
                TaskEditorDialog(
                    task = null,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                )
            }
        }

        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("Remembered task")
        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Remembered task").assertIsDisplayed()
    }

    @Test
    fun profileEditorRequiresNameAndTaskSelection() {
        val task = Task(id = 42, name = "Morning focus")
        var savedName: String? = null
        composeTestRule.setContent {
            TestTheme {
                ProfileEditorDialog(
                    profile = null,
                    tasks = listOf(task),
                    onDismiss = {},
                    onSave = { name, _, enterTaskId, _, _, _, _, _, _, _, _ ->
                        savedName = "$name:$enterTaskId"
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Create Profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("At work")
        composeTestRule.onNodeWithText("Morning focus").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("At work:42", savedName)
    }

    @Test
    fun profileEditorCanSelectAndClearAnExitTask() {
        val enter = Task(id = 42, name = "Enter")
        val cleanup = Task(id = 43, name = "Cleanup")
        var savedExitTaskId: Long? = null
        composeTestRule.setContent {
            TestTheme {
                ProfileEditorDialog(
                    profile = Profile(id = 7, name = "Work", enterTaskId = enter.id),
                    tasks = listOf(enter, cleanup),
                    onDismiss = {},
                    onSave = { _, _, _, exitTaskId, _, _, _, _, _, _, _ -> savedExitTaskId = exitTaskId },
                )
            }
        }

        composeTestRule.onNode(hasText("Exit task") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.onNode(hasText("Cleanup (43)") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(43L, savedExitTaskId)

        composeTestRule.onNode(hasText("Exit task") and hasClickAction()).performScrollTo().performClick()
        composeTestRule.onNode(hasText("None") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Save").performClick()
        assertNull(savedExitTaskId)
    }

    @Test
    fun taskListExposesAtomicActionReorderControls() {
        var moved: Pair<Int, Int>? = null
        val task = Task(
            id = 7,
            name = "Ordered",
            actions = listOf(
                ActionSpec(type = "test.first", label = "First"),
                ActionSpec(type = "test.second", label = "Second"),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                TasksScreen(
                    tasks = listOf(task),
                    storageDecodeIssues = emptyList(),
                    onCreateTask = {},
                    onEditTask = {},
                    onDeleteTask = {},
                    onRunTask = {},
                    onPreflightTask = {},
                    onPinTask = {},
                    onAddAction = {},
                    onEditAction = { _, _, _ -> },
                    onDeleteAction = { _, _ -> },
                    onMoveAction = { _, fromIndex, toIndex -> moved = fromIndex to toIndex },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Move action 2, Second, up")
            .performScrollTo()
            .performClick()

        assertEquals(1 to 0, moved)
    }

    @Test
    fun actionEditorBlocksMissingRequiredFields() {
        var actionSaved = false
        val metadata = ActionMetadata(
            id = "test.required",
            nameRes = R.string.catalog_action_notify_show_name,
            descriptionRes = R.string.catalog_action_notify_show_description,
            categoryRes = R.string.catalog_category_notification,
            fields = listOf(ActionField("message", R.string.catalog_action_notify_show_field_text_label, required = true)),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                    ),
                    onDismiss = {},
                    onSave = { actionSaved = true },
                )
            }
        }

        // "Show Notification" is the dialog title and appears again in the action summary.
        composeTestRule.onAllNodesWithText("Show Notification").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithText("Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!actionSaved)
    }

    @Test
    fun actionEditorRoundTripsConditionAndContinueOnError() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField("millis", R.string.catalog_action_flow_wait_field_millis_label, required = true),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                        existing = ActionSpec(
                            id = 9,
                            type = "flow.wait",
                            args = mapOf("millis" to "1"),
                            condition = "%old == true",
                        ),
                    ),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        composeTestRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeTestRule.onAllNodes(hasSetTextAction())[1].performTextInput("%armed == true")
        composeTestRule.onNodeWithTag(ACTION_CONTINUE_ON_ERROR_TAG).performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("%armed == true", saved?.condition)
        assertTrue(saved?.continueOnError == true)
        assertEquals(mapOf("millis" to "1"), saved?.args)
    }

    @Test
    fun actionEditorDropdownStoresTheStableOptionValue() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_wifi_toggle_name,
            descriptionRes = R.string.catalog_action_wifi_toggle_description,
            categoryRes = R.string.catalog_category_settings,
            fields = listOf(
                ActionField(
                    key = "state",
                    labelRes = R.string.catalog_action_wifi_toggle_field_state_label,
                    fieldType = FieldType.DROPDOWN,
                    required = true,
                    options = listOf(
                        ActionFieldOption("enabled_wire_value", R.string.label_on),
                        ActionFieldOption("disabled_wire_value", R.string.label_off),
                    ),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(Task(id = 7, name = "Task"), metadata),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNode(hasText("State *") and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("On").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("enabled_wire_value", saved?.args?.get("state"))
    }

    @Test
    fun actionEditorBlocksInvalidNumberSyntax() {
        val metadata = ActionMetadata(
            id = "flow.wait",
            nameRes = R.string.catalog_action_flow_wait_name,
            descriptionRes = R.string.catalog_action_flow_wait_description,
            categoryRes = R.string.catalog_category_flow,
            fields = listOf(
                ActionField(
                    key = "millis",
                    labelRes = R.string.catalog_action_flow_wait_field_millis_label,
                    fieldType = FieldType.NUMBER,
                    required = true,
                    numberRule = ActionNumberRule(minimum = 0.0, maximum = 10.0),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(Task(id = 7, name = "Task"), metadata),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Milliseconds *") and hasSetTextAction()).performTextInput("1e3")
        composeTestRule.onNodeWithText("Enter a valid number.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun actionEditorPreservesArgumentsUnknownToItsMetadata() {
        var saved: ActionSpec? = null
        val metadata = ActionMetadata(
            id = "log",
            nameRes = R.string.catalog_action_log_name,
            descriptionRes = R.string.catalog_action_log_description,
            categoryRes = R.string.catalog_category_system,
            fields = listOf(ActionField("message", R.string.catalog_action_log_field_message_label, required = true)),
        )
        val opaque = "  newer-format\u0000value\r\n  "
        composeTestRule.setContent {
            TestTheme {
                ActionConfigDialog(
                    state = ActionEditState(
                        task = Task(id = 7, name = "Task"),
                        metadata = metadata,
                        existing = ActionSpec(
                            id = 9,
                            type = metadata.id,
                            args = linkedMapOf("future.argument" to opaque, "message" to "hello"),
                        ),
                    ),
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(opaque, saved?.args?.get("future.argument"))
        assertEquals("hello", saved?.args?.get("message"))
    }

    @Test
    fun contextEditorBlocksMissingRequiredFields() {
        var contextSaved = false
        composeTestRule.setContent {
            TestTheme {
                ContextConfigDialog(
                    state = ContextEditState(
                        profile = Profile(id = 3, name = "Profile", enterTaskId = 7),
                        type = ContextType.APPLICATION,
                    ),
                    onDismiss = {},
                    onSave = { contextSaved = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Application").assertIsDisplayed()
        composeTestRule.onNodeWithText("Invert match").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!contextSaved)
    }

    @Test
    fun sceneCreationDialogValidatesNameBeforeSave() {
        var createdScene: String? = null
        composeTestRule.setContent {
            TestTheme {
                SceneLibraryScreen(
                    scenes = emptyList(),
                    tasks = emptyList(),
                    onCreateScene = { name, width, height -> createdScene = "$name:$width:$height" },
                    onUpdateScene = { _, _ -> },
                    onDeleteScene = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }

        composeTestRule.onNodeWithText("No scenes yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create Scene").performClick()
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("HUD")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()

        assertEquals("HUD:320:240", createdScene)
    }

    @Test
    fun incompatibleBundleReviewKeepsImportDisabled() {
        composeTestRule.setContent {
            TestTheme {
                OpenTaskerBundleReviewDialog(
                    state = OpenTaskerBundleReviewState(
                        bundle = OpenTaskerBundle(
                            schemaVersion = 999,
                            appVersion = "0.0.0",
                            exportedAtEpochMs = 0,
                        ),
                        plan = BundleImportPlan(
                            canImport = false,
                            warnings = listOf("Unsupported schema version 999."),
                        ),
                    ),
                    busy = false,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Review OpenTasker bundle").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cannot import").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import Disabled").assertIsNotEnabled()
    }

    @Test
    fun bundleVariableConflictRequiresAnExplicitChoice() {
        val review = mutableStateOf(
            OpenTaskerBundleReviewState(
                bundle = OpenTaskerBundle(
                    appVersion = "0.2.79",
                    exportedAtEpochMs = 0,
                ),
                plan = BundleImportPlan(
                    canImport = true,
                    variableConflicts = listOf(
                        VariableImportConflict(
                            name = "API_TOKEN",
                            existingIsSecret = true,
                            suggestedRename = "API_TOKEN_imported",
                        ),
                    ),
                ),
            ),
        )
        composeTestRule.setContent {
            TestTheme {
                OpenTaskerBundleReviewDialog(
                    state = review.value,
                    busy = false,
                    onDismiss = {},
                    onVariableConflictResolution = { name, resolution ->
                        review.value = review.value.copy(
                            variableResolutions = review.value.variableResolutions + (name to resolution),
                        )
                    },
                    onConfirm = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Import Disabled").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Keep existing").performClick()
        composeTestRule.onNodeWithText("Selected: Keep existing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import for Review").assertIsEnabled()
        assertEquals(
            VariableConflictResolution(VariableConflictAction.PRESERVE_EXISTING),
            review.value.variableResolutions["API_TOKEN"],
        )
    }

    @Test
    fun installedAppPickerSearchesLabelsAndPackages() {
        var selected: InstalledApp? = null
        composeTestRule.setContent {
            TestTheme {
                InstalledAppPickerDialog(
                    appsOverride = listOf(
                        InstalledApp("com.android.chrome", "Chrome"),
                        InstalledApp("com.spotify.music", "Spotify"),
                    ),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Search by app or package").performTextInput("spotify.music")
        composeTestRule.onNodeWithText("Spotify").assertIsDisplayed().performClick()
        assertEquals(InstalledApp("com.spotify.music", "Spotify"), selected)
    }

    @Test
    fun installedAppPickerOffersLatestInspectorObservation() {
        var selected: InstalledApp? = null
        composeTestRule.setContent {
            TestTheme {
                InstalledAppPickerDialog(
                    suggestedPackage = "com.spotify.music",
                    appsOverride = listOf(InstalledApp("com.spotify.music", "Spotify")),
                    onDismiss = {},
                    onSelect = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Use latest observed: Spotify (com.spotify.music)").performClick()
        assertEquals(InstalledApp("com.spotify.music", "Spotify"), selected)
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        OpenTaskerTheme(content = content)
    }
}
