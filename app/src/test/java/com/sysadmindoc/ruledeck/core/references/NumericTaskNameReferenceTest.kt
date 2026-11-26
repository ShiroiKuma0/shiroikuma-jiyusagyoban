package com.sysadmindoc.ruledeck.core.references

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
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

    /** At runtime a button's id key wins whenever it's there, even blank, so its old name means nothing. */
    @Test
    fun aButtonsLegacyNameCountsOnlyWithoutAnIdKey() {
        val foo = Task(id = 3, name = "Foo")
        val caller = caller(
            ActionSpec(type = "task.run", args = mapOf("task" to "3")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task_id" to "9", "button1_task" to "Foo")),
            ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task_id" to "", "button1_task" to "Foo")),
        )
        val all = listOf(foo, Task(id = 9, name = "Nine"), caller)

        val references = AutomationReferenceIndex.referencesTo(task = foo, tasks = all)
        assertEquals(listOf(0), references.map { (it.site as TaskReferenceSite.SubTaskRun).actionIndex })

        val actions = AutomationReferenceRewriter.retarget(foo, ReferenceResolution.Clear, tasks = all).tasks.single().actions
        assertNull(actions[0].args["task"])
        assertEquals(caller.actions[1], actions[1])
        assertEquals(caller.actions[2], actions[2])
    }

    /** A button's legacy name runs only the task with exactly that name; "foo" is not "Foo". */
    @Test
    fun aButtonsLegacyNameMatchesOnlyItsExactTask() {
        val upper = Task(id = 3, name = "Foo")
        val lower = Task(id = 4, name = "foo")
        val caller = caller(ActionSpec(type = "notify.show", args = mapOf("button1_label" to "Go", "button1_task" to "foo")))
        val all = listOf(upper, lower, caller)

        assertTrue(AutomationReferenceIndex.referencesTo(task = upper, tasks = all).isEmpty())
        assertEquals(1, AutomationReferenceIndex.referencesTo(task = lower, tasks = all).size)
        // A rewrite lists only the tasks it changed.
        assertTrue(AutomationReferenceRewriter.retarget(upper, ReferenceResolution.Clear, tasks = all).tasks.isEmpty())
        assertTrue(AutomationReferenceRewriter.stabilizeNameReferences(target = upper, tasks = all).tasks.isEmpty())
    }

    /** As the engine reads it, a number that is a bundle task's id means that task, even when another is named with it. */
    @Test
    fun importReadsABundleIdBeforeATaskNamedWithIt() {
        // Bundle task 4 "Alpha" went in as 104, and bundle task 7, named "4", as 107.
        val rewrite = AutomationReferenceRewriter.remapIds(
            idMap = mapOf(4L to 104L, 7L to 107L, 30L to 130L),
            tasks = listOf(
                Task(id = 104, name = "Alpha"),
                Task(id = 107, name = "4"),
                Task(id = 130, name = "Caller", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "4")))),
            ),
        )

        assertEquals("104", rewrite.tasks.single { it.id == 130L }.actions.single().args["task"])
    }

    /** A bundle's call to its task named "1" would, after the import, run whatever task here has id 1. */
    @Test
    fun importPinsACallToANumericNameToTheImportedTask() {
        // The bundle's task "1" had id 4 and its caller id 30. They went in as 104 and 130.
        val rewrite = AutomationReferenceRewriter.remapIds(
            idMap = mapOf(4L to 104L, 30L to 130L),
            tasks = listOf(
                Task(id = 104, name = "1"),
                Task(
                    id = 130,
                    name = "Caller",
                    actions = listOf(
                        ActionSpec(type = "task.run", args = mapOf("task" to "1")),
                        ActionSpec(type = "task.stop", args = mapOf("task" to "4")),
                        ActionSpec(type = "task.run", args = mapOf("task" to "77")),
                    ),
                ),
            ),
        )

        assertEquals(listOf("104", "104", "77"), rewrite.tasks.single().actions.map { it.args["task"] })
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
