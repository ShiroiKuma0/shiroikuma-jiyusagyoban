package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneValidatorTest {
    @Test
    fun validateWarnsForEmptyScene() {
        val scene = Scene(id = 1, name = "Panel", widthDp = 320, heightDp = 240)

        val issues = SceneValidator.validate(scene, emptyList())

        assertEquals(listOf(SceneIssue(SceneIssueSeverity.WARNING, "Scene has no elements.")), issues)
    }

    @Test
    fun validateReportsGeometryAndMissingTaskIssues() {
        val scene = Scene(
            id = 1,
            name = "Panel",
            widthDp = 200,
            heightDp = 120,
            elements = listOf(
                SceneElement(
                    id = 7,
                    type = SceneElementType.BUTTON,
                    xDp = 190,
                    yDp = -1,
                    widthDp = 40,
                    heightDp = 0,
                    tapTaskId = 99,
                ),
            ),
        )

        val issues = SceneValidator.validate(scene, tasks = listOf(Task(id = 12, name = "Existing")))

        assertTrue(issues.any { it.severity == SceneIssueSeverity.ERROR && "non-positive size" in it.message })
        assertTrue(issues.any { it.severity == SceneIssueSeverity.ERROR && "starts outside" in it.message })
        assertTrue(issues.any { it.severity == SceneIssueSeverity.WARNING && "extends beyond" in it.message })
        assertTrue(issues.any { it.severity == SceneIssueSeverity.ERROR && "missing tap task 99" in it.message })
    }

    @Test
    fun validateAcceptsBoundedElementWithExistingTasks() {
        val task = Task(id = 12, name = "Run")
        val scene = Scene(
            id = 1,
            name = "Panel",
            widthDp = 200,
            heightDp = 120,
            elements = listOf(
                SceneElement(
                    id = 1,
                    type = SceneElementType.TEXT,
                    xDp = 10,
                    yDp = 20,
                    widthDp = 100,
                    heightDp = 32,
                    tapTaskId = task.id,
                ),
            ),
        )

        val issues = SceneValidator.validate(scene, listOf(task))

        assertTrue(issues.isEmpty())
    }
}
