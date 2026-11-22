package com.opentasker.widget

import com.opentasker.widget.TaskShortcutHelper.ShortcutState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskShortcutPlanTest {

    @Test
    fun aRenamedTasksShortcutTakesItsNewName() {
        val plan = TaskShortcutHelper.plan(listOf(ShortcutState("task_4", 4, "Old", enabled = true)), mapOf(4L to "New"))

        assertEquals(listOf(ShortcutState("task_4", 4, "New", enabled = true)), plan.relabel)
        assertEquals(emptyList<String>(), plan.disable)
    }

    @Test
    fun aDeletedTasksShortcutIsDisabledOnce() {
        val plan = TaskShortcutHelper.plan(
            listOf(ShortcutState("task_4", 4, "Gone", enabled = true), ShortcutState("task_5", 5, "Gone too", enabled = false)),
            emptyMap(),
        )

        assertEquals(listOf("task_4"), plan.disable)
        assertEquals(emptyList<ShortcutState>(), plan.relabel)
        assertEquals(emptyList<String>(), plan.enable)
    }

    /** Undoing a delete puts the task back under its old id. */
    @Test
    fun aShortcutComesBackWithItsTask() {
        val plan = TaskShortcutHelper.plan(listOf(ShortcutState("task_4", 4, "Back", enabled = false)), mapOf(4L to "Back"))

        assertEquals(listOf("task_4"), plan.enable)
        assertEquals(emptyList<ShortcutState>(), plan.relabel)
    }

    /** The publish action takes its own id and label, so a rename leaves that label alone. */
    @Test
    fun aLabelPickedWhenPublishingIsKept() {
        val plan = TaskShortcutHelper.plan(listOf(ShortcutState("morning", 4, "Wake up", enabled = true)), mapOf(4L to "Morning routine"))

        assertEquals(emptyList<ShortcutState>(), plan.relabel)
        assertEquals(emptyList<String>(), plan.disable)
    }
}
