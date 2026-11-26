package com.sysadmindoc.ruledeck.ui.screens

import com.sysadmindoc.ruledeck.core.engine.ActiveExecution
import com.sysadmindoc.ruledeck.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Running pill's rule, and the sort being a view that leaves the list it was given alone. */
class RunningTasksTest {
    private fun run(taskId: Long, name: String) =
        ActiveExecution(id = taskId, taskId = taskId, taskName = name, source = "Manual", startedAtMs = 0L)

    @Test
    fun aRunAndItsSubTasksBothCount() {
        val running = runningTasks(listOf(run(7, "Outer")), nestedTaskIds = listOf(9))
        assertTrue(running.runs(7))
        assertTrue(running.runs(9))
        assertFalse(running.runs(8))
    }

    @Test
    fun aProfileSaysRunningForItsEnterExitOrFallbackTask() {
        val running = runningTasks(listOf(run(7, "Outer")), emptyList())
        assertTrue(running.runs(Profile(name = "p", enterTaskId = 7)))
        assertTrue(running.runs(Profile(name = "p", enterTaskId = 1, exitTaskId = 7)))
        assertTrue(running.runs(Profile(name = "p", enterTaskId = 1, fallbackTaskId = 7)))
        assertFalse(running.runs(Profile(name = "p", enterTaskId = 1)))
    }

    @Test
    fun aProfileLinkedByNameSaysRunningEvenWhenItsIdIsStale() {
        // After a bundle re-import the task has a new id; the profile still names it.
        val running = runningTasks(listOf(run(42, "Chime")), emptyList())
        assertTrue(running.runs(Profile(name = "p", enterTaskId = 3, enterTaskName = "Chime")))
        assertFalse(running.runs(Profile(name = "p", enterTaskId = 3, enterTaskName = "Other")))
    }

    @Test
    fun nothingRunningMeansNoPill() {
        assertEquals(RunningTasks(), runningTasks(emptyList(), emptyList()))
    }

    @Test
    fun sortingIsAViewAndLeavesTheGivenOrderAlone() {
        val arranged = listOf("delta" to 1L, "Alpha" to 3L, "charlie" to null, "Bravo" to 2L)
        val before = arranged.toList()
        assertEquals(listOf("Alpha", "Bravo", "charlie", "delta"), sortLibrary(arranged, LibrarySortOrder.Name, { it.first }) { it.second }.map { it.first })
        // Newest first; never edited goes last, in the order it came.
        assertEquals(listOf("Alpha", "Bravo", "delta", "charlie"), sortLibrary(arranged, LibrarySortOrder.LastEdited, { it.first }) { it.second }.map { it.first })
        assertEquals(arranged, sortLibrary(arranged, LibrarySortOrder.Default, { it.first }) { it.second })
        assertEquals(before, arranged)
    }
}
