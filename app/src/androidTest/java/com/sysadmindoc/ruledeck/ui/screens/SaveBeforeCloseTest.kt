package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.sysadmindoc.ruledeck.core.model.Project
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Editors stay open until the view model accepts the save, and reordering stops at the ends (A-333). */
class SaveBeforeCloseTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun aRejectedVariableSaveKeepsTheDialogOpenAndAnAcceptedOneClosesIt() {
        var accept = false
        val attempts = mutableListOf<String>()
        composeTestRule.setContent {
            RuleDeckTheme {
                VariablesScreen(
                    variables = emptyList(),
                    contentPadding = PaddingValues(0.dp),
                    onUpdate = { _, name, _, _, _, _, onSaved ->
                        attempts += name
                        if (accept) onSaved()
                    },
                    onDelete = { _, _, _ -> },
                    onMessage = {},
                )
            }
        }

        composeTestRule.onNodeWithText("New variable").performClick()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("api_key")
        composeTestRule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-value")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()
        // The save was refused, so the dialog and what was typed are still there to fix or retry.
        composeTestRule.onNodeWithText("secret-value").assertIsDisplayed()

        accept = true
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("secret-value").assertDoesNotExist()
        assertEquals(2, attempts.size)
    }

    @Test
    fun theFirstProjectCantMoveUpAndTheLastCantMoveDown() {
        val moves = mutableListOf<Pair<String, Int>>()
        val projects = listOf(Project(id = 2L, name = "Home", position = 0), Project(id = 3L, name = "Work", position = 1))
        composeTestRule.setContent {
            RuleDeckTheme {
                ProjectScopeBar(
                    projects = projects,
                    selectedProjectId = null,
                    onSelectProject = {},
                    onCreateProject = { _, _ -> },
                    onRenameProject = { _, _, _ -> },
                    onReorderProject = { project, direction -> moves += project.name to direction },
                    onDeleteProject = { _, _ -> },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Manage projects").performClick()
        // Each move button names its project (A-339).
        composeTestRule.onNodeWithContentDescription("Move project Home up").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Move project Work down").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Move project Work up").assertIsEnabled().performClick()
        composeTestRule.onNodeWithContentDescription("Move project Home down").assertIsEnabled().performClick()

        assertEquals(listOf("Work" to -1, "Home" to 1), moves)
    }
}
