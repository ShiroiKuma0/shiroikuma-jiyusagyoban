package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * While a task runs, its card and every profile that runs it say Running, read out with the
 * card's status line, and the pill goes when the run ends or is cancelled (A-281). The runs come
 * from a stand-in for the live execution registry, through the same mapping the app uses.
 */
class RunningBadgeComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val live = MutableStateFlow<List<ActiveExecution>>(emptyList())
    private val lights = Task(id = 7, name = "Lights", actions = listOf(ActionSpec(type = "test.first", label = "First")))
    private val fan = Task(id = 8, name = "Fan", actions = listOf(ActionSpec(type = "test.first", label = "First")))

    @Test
    fun aTaskCardSaysRunningOnlyWhileItsTaskRuns() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                val executions by live.collectAsState()
                TasksScreen(
                    tasks = listOf(lights, fan),
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
                    onMoveAction = { _, _, _ -> },
                    onRunAction = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                    runningTaskIds = runningTaskIds(executions),
                )
            }
        }
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)

        live.value = listOf(run(lights))
        composeTestRule.onNodeWithText(RUNNING).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(1)

        // Finished or cancelled, the run leaves the registry and the pill goes with it.
        live.value = emptyList()
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)
    }

    @Test
    fun everyProfileThatRunsTheTaskSaysRunning() {
        val onEnter = Profile(id = 1, name = "Evening", enterTaskId = lights.id)
        val onExit = Profile(id = 2, name = "Leaving", enterTaskId = fan.id, exitTaskId = lights.id)
        val other = Profile(id = 3, name = "Hot day", enterTaskId = fan.id)
        composeTestRule.setContent {
            OpenTaskerTheme {
                val executions by live.collectAsState()
                ProfilesScreen(
                    profiles = listOf(onEnter, onExit, other),
                    tasks = listOf(lights, fan),
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
                    runningTaskIds = runningTaskIds(executions),
                )
            }
        }
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)

        live.value = listOf(run(lights))
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(2)

        live.value = listOf(run(fan))
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(2)

        live.value = listOf(run(lights), run(fan))
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(3)

        live.value = emptyList()
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)
    }

    @Test
    fun aProfilesFallbackTaskAndASubTaskCountAsRunning() {
        val guarded = Profile(id = 4, name = "Guarded", enterTaskId = lights.id, fallbackTaskId = fan.id)
        val nested = MutableStateFlow<List<Long>>(emptyList())
        composeTestRule.setContent {
            OpenTaskerTheme {
                val executions by live.collectAsState()
                val nestedIds by nested.collectAsState()
                ProfilesScreen(
                    profiles = listOf(guarded),
                    tasks = listOf(lights, fan),
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
                    runningTaskIds = runningTaskIds(executions, nestedIds),
                )
            }
        }
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)

        live.value = listOf(run(fan))
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(1)

        // The fallback task running inside another run, as a sub-task, still counts.
        live.value = emptyList()
        nested.value = listOf(fan.id)
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(1)

        nested.value = emptyList()
        composeTestRule.onAllNodesWithText(RUNNING).assertCountEquals(0)
    }

    private fun run(task: Task) = ActiveExecution(
        id = task.id * 100,
        taskId = task.id,
        taskName = task.name,
        source = "Manual",
        startedAtMs = 0L,
    )

    private companion object {
        const val RUNNING = "Running"
    }
}
