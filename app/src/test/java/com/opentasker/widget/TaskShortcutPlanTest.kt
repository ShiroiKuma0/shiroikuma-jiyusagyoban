package com.opentasker.widget

import com.opentasker.widget.TaskShortcutHelper.ShortcutState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskShortcutPlanTest {

    private fun pinned(taskId: Long, label: String, enabled: Boolean = true) =
        ShortcutState("task_$taskId", taskId, label, enabled, followsTask = true)

    @Test
    fun aRenamedTasksShortcutTakesItsNewName() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Old")), mapOf(4L to "New"))

        assertEquals(listOf(pinned(4, "New")), plan.relabel)
        assertEquals(emptyList<String>(), plan.disable)
    }

    @Test
    fun aDeletedTasksShortcutIsDisabledOnce() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Gone"), pinned(5, "Gone too", enabled = false)), emptyMap())

        assertEquals(listOf("task_4"), plan.disable)
        assertEquals(emptyList<ShortcutState>(), plan.relabel)
        assertEquals(emptyList<String>(), plan.enable)
    }

    /** Undoing a delete puts the task back under its old id and name. */
    @Test
    fun aShortcutComesBackWithItsTask() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Back", enabled = false)), mapOf(4L to "Back"))

        assertEquals(listOf("task_4"), plan.enable)
        assertEquals(emptyList<ShortcutState>(), plan.relabel)
    }

    /** After a restore a different task can take the id; its shortcut stays off rather than run it. */
    @Test
    fun aDifferentTaskUnderTheSameIdLeavesTheShortcutOff() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Old task", enabled = false)), mapOf(4L to "Someone else"))

        assertEquals(emptyList<String>(), plan.enable)
        assertEquals(emptyList<ShortcutState>(), plan.relabel)
    }

    /** The publish action takes its own label, even under a `task_<id>` id, so a rename leaves it alone. */
    @Test
    fun aLabelPickedWhenPublishingIsKept() {
        val picked = ShortcutState("task_4", 4, "Wake up", enabled = true, followsTask = false)
        val plan = TaskShortcutHelper.plan(listOf(picked), mapOf(4L to "Morning routine"))

        assertEquals(emptyList<ShortcutState>(), plan.relabel)
        assertEquals(emptyList<String>(), plan.disable)
    }

    /** With no name to check it against, a picked label can't tell its own task from a stranger. */
    @Test
    fun aPickedLabelStaysOffOnceItsTaskIsGone() {
        val picked = ShortcutState("morning", 4, "Wake up", enabled = false, followsTask = false)
        val plan = TaskShortcutHelper.plan(listOf(picked), mapOf(4L to "Wake up"))

        assertEquals(emptyList<String>(), plan.enable)
    }
}
