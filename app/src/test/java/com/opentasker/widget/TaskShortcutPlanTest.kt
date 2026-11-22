package com.opentasker.widget

import com.opentasker.widget.TaskShortcutHelper.ShortcutKind
import com.opentasker.widget.TaskShortcutHelper.ShortcutState
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskShortcutPlanTest {

    private fun pinned(taskId: Long, label: String, enabled: Boolean = true) =
        ShortcutState("task_$taskId", taskId, label, enabled, ShortcutKind.FOLLOWS, madeFor = label)

    private fun picked(taskId: Long, label: String, madeFor: String?, enabled: Boolean = true) =
        ShortcutState("task_$taskId", taskId, label, enabled, ShortcutKind.PICKED, madeFor)

    private fun older(taskId: Long, label: String, enabled: Boolean = true) =
        ShortcutState("task_$taskId", taskId, label, enabled, ShortcutKind.LEGACY)

    @Test
    fun aRenamedTasksShortcutTakesItsNewName() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Old")), mapOf(4L to "New"))

        assertEquals(listOf(pinned(4, "New")), plan.update)
        assertEquals(emptyList<String>(), plan.disable)
    }

    @Test
    fun aDeletedTasksShortcutIsDisabledOnce() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Gone"), pinned(5, "Gone too", enabled = false)), emptyMap())

        assertEquals(listOf("task_4"), plan.disable)
        assertEquals(emptyList<ShortcutState>(), plan.update)
        assertEquals(emptyList<String>(), plan.enable)
    }

    /** Undoing a delete puts the task back under its old id and name. */
    @Test
    fun aShortcutComesBackWithItsTask() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Back", enabled = false)), mapOf(4L to "Back"))

        assertEquals(listOf("task_4"), plan.enable)
        assertEquals(emptyList<ShortcutState>(), plan.update)
    }

    /** After a restore a different task can take the id; its shortcut stays off rather than run it. */
    @Test
    fun aDifferentTaskUnderTheSameIdLeavesTheShortcutOff() {
        val plan = TaskShortcutHelper.plan(listOf(pinned(4, "Old task", enabled = false)), mapOf(4L to "Someone else"))

        assertEquals(emptyList<String>(), plan.enable)
        assertEquals(emptyList<ShortcutState>(), plan.update)
    }

    /** The publish action takes its own label, even under a `task_<id>` id, so a rename keeps it and only notes the new name. */
    @Test
    fun aLabelPickedWhenPublishingIsKept() {
        val plan = TaskShortcutHelper.plan(listOf(picked(4, "Wake up", madeFor = "Morning routine")), mapOf(4L to "Early routine"))

        assertEquals(listOf(picked(4, "Wake up", madeFor = "Early routine")), plan.update)
        assertEquals(emptyList<String>(), plan.disable)
    }

    /** A picked label used to stay grey for good after its task's delete was undone. */
    @Test
    fun aPickedLabelComesBackWithItsTaskButNotForAnother() {
        val off = picked(4, "Wake up", madeFor = "Morning routine", enabled = false)

        assertEquals(listOf("task_4"), TaskShortcutHelper.plan(listOf(off), mapOf(4L to "Morning routine")).enable)
        assertEquals(emptyList<String>(), TaskShortcutHelper.plan(listOf(off), mapOf(4L to "Wake up")).enable)
    }

    /**
     * Shortcuts from older versions carry no kind. One published with its own label used to be
     * renamed to its task's name on the first start after an update. The first pass that finds
     * the task now records what each is for and keeps a label that isn't the task's name.
     */
    @Test
    fun anOlderShortcutKeepsItsLabelUnlessItWasTheTasksName() {
        val publishedEarlier = older(4, "Wake up")
        val pinnedEarlier = older(5, "Old")
        val names = mapOf(4L to "Morning routine", 5L to "Old")

        assertEquals(
            listOf(picked(4, "Wake up", madeFor = "Morning routine"), pinned(5, "Old")),
            TaskShortcutHelper.plan(listOf(publishedEarlier, pinnedEarlier), names).update,
        )

        // Renamed before Android took that record, the one named after its task still follows.
        val renamed = mapOf(4L to "Early routine", 5L to "New")
        val plan = TaskShortcutHelper.plan(listOf(publishedEarlier, pinnedEarlier), renamed, previousNames = names)
        assertEquals(listOf(picked(4, "Wake up", madeFor = "Early routine"), pinned(5, "New")), plan.update)
    }

    /**
     * An older shortcut with its own label used to stay gray for good after its task's delete was
     * undone, and a restore of the same tasks turned it off too. Recorded, it acts like a new one.
     */
    @Test
    fun anOlderPickedLabelComesBackAfterAnUndoOrARestore() {
        val recorded = TaskShortcutHelper.plan(listOf(older(4, "Wake up")), mapOf(4L to "Morning routine")).update.single()

        assertEquals(listOf("task_4"), TaskShortcutHelper.plan(listOf(recorded), emptyMap()).disable)
        assertEquals(
            listOf("task_4"),
            TaskShortcutHelper.plan(listOf(recorded.copy(enabled = false)), mapOf(4L to "Morning routine")).enable,
        )
        val restored = TaskShortcutHelper.plan(listOf(recorded), mapOf(4L to "Morning routine"), afterRestore = true)
        assertEquals(emptyList<String>(), restored.disable)
        assertEquals(emptyList<ShortcutState>(), restored.update)
    }

    /** A restore swaps every task at once, so a new name under a shortcut's id may be another task. */
    @Test
    fun afterARestoreAShortcutWhoseTaskChangedIsTurnedOff() {
        val plan = TaskShortcutHelper.plan(
            listOf(pinned(4, "Old"), picked(5, "Wake up", madeFor = "Morning routine"), pinned(6, "Same")),
            mapOf(4L to "Someone else", 5L to "Bedtime", 6L to "Same"),
            afterRestore = true,
        )

        assertEquals(listOf("task_4", "task_5"), plan.disable)
        assertEquals(emptyList<ShortcutState>(), plan.update)
    }
}
