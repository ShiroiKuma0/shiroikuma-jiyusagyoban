package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.sysadmindoc.ruledeck.core.contexts.QuickSettingsTileConfig
import com.opentasker.core.contexts.TileConfigScreen
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.SceneElement
import com.sysadmindoc.ruledeck.core.model.SceneElementType
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.model.Variable
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import org.junit.Rule
import org.junit.Test

/**
 * A row's buttons say what they act on, so TalkBack and a uiautomator dump name the variable,
 * scene, element or step instead of reading "Delete" or "More" on every row, and choices that show
 * their state by colour also say it (A-339).
 */
class RowControlNamesComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun aVariableRowNamesTheVariableItDeletes() {
        composeTestRule.setContent {
            RuleDeckTheme {
                VariablesScreen(
                    variables = listOf(Variable(name = "city", value = "Boston", isGlobal = true)),
                    contentPadding = PaddingValues(0.dp),
                    onUpdate = { _, _, _, _, _, _, _ -> },
                    onDelete = { _, _, _ -> },
                    onMessage = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Delete variable city").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Delete").assertCountEquals(0)
    }

    @Test
    fun aSceneCardNamesItsSceneAndEachElementRowItsElement() {
        val scene = Scene(
            id = 1,
            name = "Panel",
            widthDp = 320,
            heightDp = 240,
            elements = listOf(
                SceneElement(id = 1, type = SceneElementType.BUTTON, xDp = 16, yDp = 16, widthDp = 120, heightDp = 48),
                SceneElement(id = 2, type = SceneElementType.IMAGE, xDp = 16, yDp = 80, widthDp = 64, heightDp = 64),
            ),
        )
        composeTestRule.setContent {
            RuleDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SceneCard(
                        scene = scene,
                        tasks = emptyList(),
                        onAddElement = {},
                        onEditElement = { _, _ -> },
                        onDeleteElement = { _, _ -> },
                        onUpdateScene = { _, _ -> },
                        canUndo = false,
                        canRedo = false,
                        onUndo = {},
                        onRedo = {},
                        onDelete = {},
                        onDuplicate = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Delete scene Panel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Panel", useUnmergedTree = true).performClick()

        for (described in listOf(
            "Edit element 1, Button",
            "Delete element 1, Button",
            "Move element 1, Button, right 1 dp",
            "Edit element 2, Image",
            "Delete element 2, Image",
            "Move element 2, Image, down 1 dp",
        )) {
            composeTestRule.onNodeWithContentDescription(described).performScrollTo().assertIsDisplayed()
        }
        composeTestRule.onAllNodesWithContentDescription("Delete element").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Delete scene").assertCountEquals(0)
    }

    @Test
    fun aStepsMenuNamesItsStep() {
        composeTestRule.setContent {
            RuleDeckTheme {
                ActionRow(
                    index = 1,
                    action = ActionSpec(type = "var.set", label = "Remember city"),
                    canMoveUp = true,
                    canMoveDown = false,
                    onMoveUp = {},
                    onMoveDown = {},
                    onEdit = {},
                    onDelete = {},
                    onRun = {},
                    runBusy = false,
                    pendingSetup = emptySet(),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More actions for action 2, Remember city").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("More").assertCountEquals(0)
    }

    @Test
    fun dayButtonsSayWhetherTheyAreSelected() {
        composeTestRule.setContent {
            RuleDeckTheme {
                Column {
                    DayPresetButton(label = "Mon", selected = true, onClick = {})
                    DayPresetButton(label = "Tue", selected = false, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Mon").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeTestRule.onNodeWithText("Tue").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }

    @Test
    fun theTileSetupSaysWhichTaskAndIconAreChosen() {
        composeTestRule.setContent {
            RuleDeckTheme {
                TileConfigScreen(
                    slot = 1,
                    initial = QuickSettingsTileConfig(slot = 1, taskId = 7, taskName = "Lights", label = "My tile"),
                    tasks = listOf(Task(id = 7, name = "Lights"), Task(id = 8, name = "Fan")),
                    onSave = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Lights")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeTestRule.onNodeWithText("Fan").assertIsNotSelected().performClick()
        composeTestRule.onNodeWithText("Fan").assertIsSelected()
        composeTestRule.onNodeWithText("Play").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        composeTestRule.onNodeWithText("Star").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
    }
}
