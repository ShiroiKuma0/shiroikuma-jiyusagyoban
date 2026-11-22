package com.opentasker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.scenes.SceneEditorMutations
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SceneEditorCanvasTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val start = Scene(
        id = 1,
        name = "Panel",
        widthDp = 400,
        heightDp = 300,
        elements = listOf(
            SceneElement(id = 11, type = SceneElementType.BUTTON, xDp = 10, yDp = 10, widthDp = 120, heightDp = 60, config = mapOf("label" to "Alpha")),
            SceneElement(id = 12, type = SceneElementType.BUTTON, xDp = 10, yDp = 180, widthDp = 120, heightDp = 60, config = mapOf("label" to "Bravo")),
        ),
    )

    /**
     * The card saves a move over the scene it composed with, like the editor does. A handler starts
     * on its element's first touch and lives until its keys change, so once B had been touched, it
     * outlived A's move, saved B over the scene from before and put A back where it started.
     */
    @Test
    fun draggingTwoElementsInTurnKeepsBothMoves() {
        var scene by mutableStateOf(start)
        composeTestRule.setContent {
            OpenTaskerTheme {
                val current = scene
                ScenePreviewBox(
                    scene = current,
                    onMoveElement = { index, xDp, yDp ->
                        scene = SceneEditorMutations.replaceElement(current, index, current.elements[index].copy(xDp = xDp, yDp = yDp))
                    },
                )
            }
        }

        tap("Bravo")
        dragRight("Alpha")
        val movedA = scene.elements[0]
        assertTrue("Alpha didn't move: $movedA", movedA.xDp > 10)

        dragRight("Bravo")
        assertEquals("Bravo's drag put Alpha back", movedA, scene.elements[0])
        assertTrue("Bravo didn't move: ${scene.elements[1]}", scene.elements[1].xDp > 10)
    }

    /**
     * Selecting B changed none of its handler's keys. Once B had been touched, its handler still
     * saw B as unselected, so dragging it next toggled it back off.
     */
    @Test
    fun draggingASelectedElementKeepsItSelected() {
        var scene by mutableStateOf(start)
        var selected by mutableStateOf(emptySet<Int>())
        composeTestRule.setContent {
            OpenTaskerTheme {
                val current = scene
                ScenePreviewBox(
                    scene = current,
                    onMoveElement = { index, xDp, yDp ->
                        scene = SceneEditorMutations.replaceElement(current, index, current.elements[index].copy(xDp = xDp, yDp = yDp))
                    },
                    selectedIndices = selected,
                    onToggleSelect = { index -> selected = if (index in selected) selected - index else selected + index },
                )
            }
        }

        tap("Bravo")
        val selectLabel = composeTestRule.activity.getString(R.string.scenes_select_element_content_description)
        val select = composeTestRule.onNodeWithContentDescription("Bravo", substring = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == selectLabel }
        composeTestRule.runOnIdle { select.action() }
        composeTestRule.waitForIdle()
        assertEquals(setOf(1), selected)

        dragRight("Bravo")
        assertEquals("the drag dropped Bravo from the selection", setOf(1), selected)
        assertTrue("Bravo didn't move: ${scene.elements[1]}", scene.elements[1].xDp > 10)
    }

    private fun tap(label: String) {
        composeTestRule.onNodeWithContentDescription(label, substring = true).performTouchInput { click(center) }
        composeTestRule.waitForIdle()
    }

    private fun dragRight(label: String) {
        composeTestRule.onNodeWithContentDescription(label, substring = true).performTouchInput {
            swipe(start = center, end = center + Offset(150.dp.toPx(), 0f), durationMillis = 400)
        }
        composeTestRule.waitForIdle()
    }
}
