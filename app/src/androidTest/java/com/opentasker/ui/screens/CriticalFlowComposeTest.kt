package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
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
                    onSave = { name, _ -> savedName = name },
                )
            }
        }

        composeTestRule.onNodeWithText("Create Task").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("Morning focus")
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals("Morning focus", savedName)
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
                    onSave = { name, _, enterTaskId, _, _, _ ->
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
    fun actionAndContextEditorsBlockMissingRequiredFields() {
        var actionSaved = false
        val metadata = ActionMetadata(
            id = "test.required",
            name = "Required action",
            description = "Requires one field.",
            category = "Test",
            fields = listOf(ActionField("message", "Message", required = true)),
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

        composeTestRule.onNodeWithText("Required action").assertIsDisplayed()
        composeTestRule.onNodeWithText("Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!actionSaved)

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

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        OpenTaskerTheme(content = content)
    }
}
