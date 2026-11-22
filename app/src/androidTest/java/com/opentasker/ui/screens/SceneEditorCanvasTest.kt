package com.opentasker.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Drags on the canvas of a real [SceneCard], which saves each move over the scene it composed with. */
class SceneEditorCanvasTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private var scene by mutableStateOf(
        Scene(
            id = 1,
            name = "Panel",
            widthDp = 400,
            heightDp = 300,
            elements = listOf(
                SceneElement(id = 11, type = SceneElementType.BUTTON, xDp = 10, yDp = 10, widthDp = 120, heightDp = 60, config = mapOf("label" to "Alpha")),
                SceneElement(id = 12, type = SceneElementType.BUTTON, xDp = 10, yDp = 180, widthDp = 120, heightDp = 60, config = mapOf("label" to "Bravo")),
            ),
        ),
    )

    /**
     * A handler starts on its element's first touch and lives until its keys change. Once B had
     * been touched, its handler outlived A's move, saved B over the scene from before and put A
     * back. With fresh state, every drag also added its element to the selection, so dragging B
     * after A moved A along with it.
     */
    @Test
    fun draggingTwoElementsInTurnKeepsBothMoves() {
        showCard()
        tap("Bravo")

        dragRight("Alpha")
        val movedA = scene.elements[0]
        assertTrue("Alpha didn't move: $movedA", movedA.xDp > 10)

        dragRight("Bravo")
        assertEquals("Bravo's drag moved Alpha", movedA, scene.elements[0])
        assertTrue("Bravo didn't move: ${scene.elements[1]}", scene.elements[1].xDp > 10)
    }

    /** Selecting B changed none of its handler's keys, so its handler saw B as unselected and toggled it off. */
    @Test
    fun draggingASelectedElementKeepsItSelected() {
        showCard()
        tap("Bravo")
        assertSelected("Bravo", true)

        dragRight("Bravo")
        assertSelected("Bravo", true)
        assertTrue("Bravo didn't move: ${scene.elements[1]}", scene.elements[1].xDp > 10)
    }

    @Test
    fun tappedElementsMoveTogether() {
        showCard()
        tap("Alpha")
        tap("Bravo")
        assertSelected("Alpha", true)
        assertSelected("Bravo", true)

        dragRight("Bravo")
        val (a, b) = scene.elements
        assertTrue("Bravo didn't move: $b", b.xDp > 10)
        assertEquals("Alpha didn't move with Bravo", b.xDp, a.xDp)
        assertEquals(10, a.yDp)
        assertEquals(180, b.yDp)
    }

    private fun showCard() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                SceneCard(
                    scene = scene,
                    tasks = emptyList(),
                    onAddElement = {},
                    onEditElement = { _, _ -> },
                    onDeleteElement = { _, _ -> },
                    onUpdateScene = { updated, _ -> scene = updated },
                    canUndo = false,
                    canRedo = false,
                    onUndo = {},
                    onRedo = {},
                    onDelete = {},
                    onDuplicate = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Panel", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
    }

    private fun element(label: String) = composeTestRule.onNodeWithContentDescription("$label, at ", substring = true)

    private fun tap(label: String) {
        element(label).performTouchInput { click(center) }
        composeTestRule.waitForIdle()
    }

    /**
     * Drags in two halves with a frame between them, as a finger does. One batched swipe never
     * recomposes between the drag's start and its end, so it can't see a selection the start changed.
     */
    private fun dragRight(label: String) {
        val node = element(label)
        node.performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 16) }
        }
        composeTestRule.waitForIdle()
        node.performTouchInput {
            repeat(10) { moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 16) }
            up()
        }
        composeTestRule.waitForIdle()
    }

    private fun assertSelected(label: String, selected: Boolean) {
        val state = composeTestRule.activity.getString(if (selected) R.string.a11y_selected else R.string.a11y_not_selected)
        element(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
    }
}
