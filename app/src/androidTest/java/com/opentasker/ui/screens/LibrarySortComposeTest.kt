package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A-364 through the real Tasks list: it shows tasks in the chosen order and saves a new choice. */
class LibrarySortComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val tasks = listOf(
        Task(id = 1, name = "Charlie", actions = listOf(ActionSpec(type = "torch.set"))),
        Task(id = 2, name = "alpha", actions = listOf(ActionSpec(type = "torch.set"))),
        Task(id = 3, name = "Bravo", actions = listOf(ActionSpec(type = "torch.set"))),
    )

    private fun showTasks(control: () -> LibrarySortControl, history: EditHistoryAvailabilityState = EditHistoryAvailabilityState()) {
        composeTestRule.setContent {
            OpenTaskerTheme {
                TasksScreen(
                    tasks = tasks,
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
                    onRunAction = { _, _ -> },
                    onMoveAction = { _, _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                    historyAvailability = history,
                    librarySort = control(),
                )
            }
        }
    }

    /** Task names top to bottom, read from where each card's title sits. */
    private fun shownOrder(): List<String> = tasks.map { it.name }
        .sortedBy { name -> composeTestRule.onAllNodesWithText(name).onFirst().fetchSemanticsNode().boundsInRoot.top }

    @Test
    fun theDefaultKeepsTheListsOwnOrderAndNameSortsIt() {
        var order by mutableStateOf(LibrarySortOrder.Default)
        showTasks({ LibrarySortControl(order) { order = it } })

        assertEquals(listOf("Charlie", "alpha", "Bravo"), shownOrder())

        order = LibrarySortOrder.Name
        composeTestRule.waitForIdle()
        assertEquals(listOf("alpha", "Bravo", "Charlie"), shownOrder())
    }

    @Test
    fun lastEditedPutsTheNewestEditFirst() {
        val history = EditHistoryAvailabilityState(
            lastEditedByKey = mapOf(
                EditHistoryAvailabilityState.key(EditHistoryDao.TYPE_TASK, 3) to 200L,
                EditHistoryAvailabilityState.key(EditHistoryDao.TYPE_TASK, 1) to 100L,
            ),
        )
        showTasks({ LibrarySortControl(LibrarySortOrder.LastEdited) }, history)

        assertEquals(listOf("Bravo", "Charlie", "alpha"), shownOrder())
    }

    @Test
    fun pickingAnOrderFromTheMenuHandsItBack() {
        var picked: LibrarySortOrder? = null
        showTasks({ LibrarySortControl(LibrarySortOrder.Default) { picked = it } })

        // Tasks are already in name order, so the menu offers Name and Last edited, not Name twice.
        composeTestRule.onNodeWithContentDescription("Sort order, Name").performClick()
        composeTestRule.onAllNodesWithText("Default order").assertCountEquals(0)
        composeTestRule.onNodeWithText("Last edited").performClick()

        assertEquals(LibrarySortOrder.LastEdited, picked)
    }

    @Test
    fun aChosenOrderIsKeptOnDisk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val stored = LibrarySortPreference.orders(context).first()
            val before = stored[LibraryList.Tasks] ?: LibrarySortOrder.Default
            try {
                LibrarySortPreference.set(context, LibraryList.Tasks, if (before == LibrarySortOrder.Name) LibrarySortOrder.LastEdited else LibrarySortOrder.Name)
                val after = LibrarySortPreference.orders(context).first()
                val chosen = if (before == LibrarySortOrder.Name) LibrarySortOrder.LastEdited else LibrarySortOrder.Name

                assertEquals(chosen, after[LibraryList.Tasks])
                // Read back from the file itself, which is what a restarted app reads.
                val onDisk = File(context.filesDir, "datastore/library_sort_prefs.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
                // The Tasks entry itself, not just any list kept under that name.
                assertTrue(onDisk, Regex("sort_tasks[\\s\\S]{1,8}${chosen.name}").containsMatchIn(onDisk))
                // Each list keeps its own order.
                assertEquals(stored[LibraryList.Scenes], after[LibraryList.Scenes])
            } finally {
                LibrarySortPreference.set(context, LibraryList.Tasks, before)
            }
        }
    }

    @Test
    fun anEditTimeIsKeptOnDiskUntilForgotten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = Long.MAX_VALUE - 7
        val key = EditHistoryAvailabilityState.key(EditHistoryDao.TYPE_TASK, id)
        runBlocking {
            try {
                LibraryEditTimes.touch(context, EditHistoryDao.TYPE_TASK, id, atMs = 1_234L)
                assertEquals(1_234L, LibraryEditTimes.times(context).first()[key])
                val onDisk = File(context.filesDir, "datastore/library_edit_times.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(key in onDisk)
            } finally {
                LibraryEditTimes.forget(context, EditHistoryDao.TYPE_TASK, id)
            }
            assertEquals(null, LibraryEditTimes.times(context).first()[key])
        }
    }
}
