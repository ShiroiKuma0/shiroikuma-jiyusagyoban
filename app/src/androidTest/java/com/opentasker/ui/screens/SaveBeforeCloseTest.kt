package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Project
import com.opentasker.ui.theme.OpenTaskerTheme
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
            OpenTaskerTheme {
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
            OpenTaskerTheme {
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
        val up = composeTestRule.onAllNodesWithContentDescription("Move project up")
        val down = composeTestRule.onAllNodesWithContentDescription("Move project down")
        up[0].assertIsNotEnabled()
        down[1].assertIsNotEnabled()
        up[1].assertIsEnabled().performClick()
        down[0].assertIsEnabled().performClick()

        assertEquals(listOf("Work" to -1, "Home" to 1), moves)
    }
}
