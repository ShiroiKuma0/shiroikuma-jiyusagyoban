package com.opentasker.core.references

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine reads a numeric `task.run` or `task.stop` target as an id when a task has that id, and
 * as a name otherwise. The index and rewriter read every number as an id, so a task named "2025"
 * was deleted without a warning and a rename left its callers pointing at nothing.
 */
class NumericTaskNameReferenceTest {

    private val numbered = Task(id = 5, name = "2025")

    private fun caller(vararg actions: ActionSpec) = Task(id = 30, name = "Caller", actions = actions.toList())

    @Test
    fun deletingATaskNamedWithANumberFindsItsCallers() {
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "2025")),
            ActionSpec(type = "task.stop", args = mapOf("task" to "2025")),
        )
        val all = listOf(numbered, caller)

        assertEquals(2, AutomationReferenceIndex.referencesTo(task = numbered, tasks = all).size)
        assertEquals(2, AutomationReferenceRewriter.retarget(numbered, ReferenceResolution.Block, tasks = all).blocked.size)
    }

    @Test
    fun aNumberThatIsATasksIdStillMeansThatTask() {
        val task2025 = Task(id = 2025, name = "Other")
        val caller = caller(ActionSpec(type = "task.run", args = mapOf("task" to "2025")))
        val all = listOf(numbered, task2025, caller)

        assertTrue(AutomationReferenceIndex.referencesTo(task = numbered, tasks = all).isEmpty())
        assertEquals(1, AutomationReferenceIndex.referencesTo(task = task2025, tasks = all).size)
    }

    @Test
    fun renamingATaskNamedWithANumberPinsItsCallersToItsId() {
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "2025")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task" to "2025")),
        )

        val actions = AutomationReferenceRewriter.stabilizeNameReferences(target = numbered, tasks = listOf(numbered, caller))
            .tasks.single().actions

        assertEquals("5", actions[0].args["task"])
        assertEquals("5", actions[1].args["button1_task_id"])
        assertNull(actions[1].args["button1_task"])
    }

    @Test
    fun renamingPinsItsOwnStopStepAndDuplicatingRebindsIt() {
        val self = numbered.copy(actions = listOf(ActionSpec(type = "task.stop", args = mapOf("task" to "2025"))))
        val taskIds = setOf(numbered.id)

        val renamed = AutomationReferenceRewriter.stabilizeOwnNameReferences(self, self.copy(name = "New year"), taskIds)
        assertEquals("5", renamed.actions.single().args["task"])

        val duplicate = AutomationReferenceRewriter.remapDuplicateSelfReferences(
            original = self,
            duplicate = self.copy(id = 6, name = "2025 copy"),
            taskIds = taskIds + 6L,
        )
        assertEquals("6", duplicate.actions.single().args["task"])
    }
}
