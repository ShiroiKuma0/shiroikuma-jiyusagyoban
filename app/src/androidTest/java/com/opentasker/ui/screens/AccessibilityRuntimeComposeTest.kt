package com.opentasker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilityRuntimeComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun profilesScreenPassesRuntimeAccessibilityChecks() {
        val task = Task(
            id = 7,
            name = "Morning focus",
            actions = listOf(ActionSpec(type = "log", label = "Record focus")),
        )
        val profile = Profile(id = 8, name = "Work hours", enterTaskId = task.id)

        composeTestRule.setContent {
            OpenTaskerTheme {
                ProfilesScreen(
                    profiles = listOf(profile),
                    tasks = listOf(task),
                    runLogs = emptyList(),
                    storageDecodeIssues = emptyList(),
                    onCreateTaskFirst = {},
                    onCreateProfile = {},
                    onBrowseTemplates = {},
                    onPreviewProfileShare = {},
                    onPreflightProfile = {},
                    onExportOpenTaskerBundle = {},
                    onImportOpenTaskerBundle = {},
                    onImportOpenTaskerBundleText = {},
                    openTaskerBundleBusy = false,
                    onImportTaskerXml = {},
                    onExportTaskerXml = {},
                    taskerImportBusy = false,
                    onEditProfile = {},
                    onDeleteProfile = {},
                    onToggleProfile = { _, _ -> },
                    onAddContext = {},
                    onEditContextLogic = {},
                    onEditContext = { _, _, _ -> },
                    onDeleteContext = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }

        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Work hours").assertIsDisplayed()
    }

    @Test
    fun destructiveDeleteDialogPassesRuntimeAccessibilityChecks() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                DeleteConfirmationDialog(
                    target = DeleteTarget.TaskTarget(Task(id = 7, name = "Remove me")),
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        composeTestRule.performAccessibilityChecks()
        composeTestRule.onNodeWithText("Remove me", substring = true).assertIsDisplayed()
    }

    @Test
    fun accessibilityChecksRejectUnlabelledIconButton() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
        }

        val failure = runCatching { composeTestRule.performAccessibilityChecks() }.exceptionOrNull()
        assertTrue("An unlabeled icon button must fail the runtime accessibility check", failure != null)
    }

    @Test
    fun accessibilityChecksRejectSmallClickableTarget() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = {}),
                )
            }
        }

        val failure = runCatching { composeTestRule.performAccessibilityChecks() }.exceptionOrNull()
        assertTrue("A sub-48dp clickable target must fail the runtime accessibility check", failure != null)
    }
}
