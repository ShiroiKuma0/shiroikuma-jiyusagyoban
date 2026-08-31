package com.opentasker.core.search

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    private val task = Task(
        id = 10,
        name = "Build report",
        actions = listOf(
            ActionSpec(
                id = 100,
                type = "notify.show",
                label = "Report ready",
                args = mapOf("text" to "%ReportName"),
            ),
        ),
    )
    private val profile = Profile(id = 20, name = "Morning report", enterTaskId = task.id)
    private val variable = Variable(name = "ReportName", value = "Quarterly report")
    private val scene = Scene(
        id = 30,
        name = "Report dashboard",
        widthDp = 320,
        heightDp = 240,
        elements = listOf(
            SceneElement(
                type = SceneElementType.BUTTON,
                xDp = 0,
                yDp = 0,
                widthDp = 100,
                heightDp = 48,
                config = mapOf("label" to "Build report"),
                tapTaskId = task.id,
            ),
        ),
    )

    @Test
    fun searchFindsNamedVariableReferencesInTasksAndActions() {
        val results = searchGlobalEntities("%reportname", listOf(profile), listOf(task), listOf(variable), listOf(scene))

        assertTrue(results.any { it.kind == GlobalSearchResultKind.VARIABLE && it.variableName == "ReportName" })
        assertTrue(results.any { it.kind == GlobalSearchResultKind.TASK && it.entityId == task.id })
        assertTrue(results.any { it.kind == GlobalSearchResultKind.ACTION && it.actionIndex == 0 })
    }

    @Test
    fun searchResolvesTaskReferencesInProfilesAndScenes() {
        val results = searchGlobalEntities("build report", listOf(profile), listOf(task), emptyList(), listOf(scene))

        assertEquals(
            setOf(GlobalSearchResultKind.PROFILE, GlobalSearchResultKind.TASK, GlobalSearchResultKind.SCENE),
            results.map { it.kind }.toSet(),
        )
    }

    @Test
    fun secretValueIsNotSearchable() {
        val secret = variable.copy(name = "ApiToken", value = "never-index-this", isSecret = true)

        val results = searchGlobalEntities("never-index-this", emptyList(), emptyList(), listOf(secret), emptyList())

        assertTrue(results.isEmpty())
    }
}
