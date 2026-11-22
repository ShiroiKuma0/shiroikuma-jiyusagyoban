package com.opentasker.widget

import com.opentasker.widget.TaskShortcutHelper.ReconcileOutcome
import com.opentasker.widget.TaskShortcutHelper.ShortcutKind
import com.opentasker.widget.TaskShortcutHelper.ShortcutState
import com.opentasker.widget.TaskShortcutHelper.WatchState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

    /**
     * A pass whose renames Android held back used to drop what the app compares with: the first
     * pass's names were lost, so an older Pin shortcut was recorded with a stale label, and a
     * restore's caution stayed on after its shortcuts were off, so a later rename grayed one.
     */
    @Test
    fun aHeldBackPassKeepsTheNamesItSawAndEndsTheRestoreCaution() {
        val first = mapOf(4L to "A")
        val start = WatchState(previous = null, afterRestore = true)

        assertEquals(WatchState(first, afterRestore = false), start.after(ReconcileOutcome.CHECKED, first))
        assertEquals(WatchState(first, afterRestore = true), start.after(ReconcileOutcome.FAILED, first))
        val held = WatchState(first, afterRestore = false)
        assertEquals(held, held.after(ReconcileOutcome.CHECKED, mapOf(4L to "B")))
        assertEquals(WatchState(mapOf(4L to "B"), afterRestore = false), held.after(ReconcileOutcome.APPLIED, mapOf(4L to "B")))
        // So the older Pin shortcut still follows from the name it was first seen with.
        assertEquals(listOf(pinned(4, "B")), TaskShortcutHelper.plan(listOf(older(4, "A")), mapOf(4L to "B"), held.previous).update)
    }

    /**
     * A rename Android held back, then a delete and its undo, used to leave the shortcut gray for
     * good: it still said the old name, so the task that came back looked like another one.
     */
    @Test
    fun aShortcutWhoseRenameWasHeldBackComesBackAfterAnUndo() {
        val shortcut = pinned(4, "A")
        val seen = WatchState(previous = mapOf(4L to "A"), afterRestore = false)
        val renamed = mapOf(4L to "B")
        val rename = TaskShortcutHelper.plan(listOf(shortcut), renamed, seen.previous, heldBack = seen.heldBack)
        assertEquals(listOf(pinned(4, "B")), rename.update)
        val held = seen.after(ReconcileOutcome.CHECKED, renamed, rename.updatedNames)

        assertEquals(listOf("task_4"), TaskShortcutHelper.plan(listOf(shortcut), emptyMap(), held.previous, heldBack = held.heldBack).disable)
        val deleted = held.after(ReconcileOutcome.APPLIED, emptyMap())

        val undo = TaskShortcutHelper.plan(listOf(shortcut.copy(enabled = false)), renamed, deleted.previous, heldBack = deleted.heldBack)
        assertEquals(listOf("task_4"), undo.enable)
        assertEquals(listOf(pinned(4, "B", enabled = false)), undo.update)
        assertEquals(emptyMap<Long, String>(), deleted.after(ReconcileOutcome.APPLIED, renamed).heldBack)
    }

    /**
     * After a restore whose first pass failed, a rename used to turn the renamed task's own
     * shortcut off, since its new name didn't match. The name first seen after the restore counts.
     */
    @Test
    fun afterARestoreARenameDoesNotMakeItAnotherTask() {
        val failed = WatchState(previous = null, afterRestore = true).after(ReconcileOutcome.FAILED, mapOf(4L to "A", 5L to "Y"))
        val plan = TaskShortcutHelper.plan(
            listOf(pinned(4, "A"), pinned(5, "X")),
            mapOf(4L to "B", 5L to "Y"),
            failed.previous,
            failed.afterRestore,
        )

        assertEquals(listOf("task_5"), plan.disable)
        assertEquals(listOf(pinned(4, "B")), plan.update)
    }

    /** A pass that failed outright used to wait for the next edit, so a restore's check could wait for days. */
    @Test
    fun aFailedPassIsTriedAgain() = runBlocking {
        val seen = mutableListOf<WatchState>()
        val watcher = TaskShortcutHelper.Watcher(afterRestore = true, retryDelayMs = 1L) { _, state ->
            seen += state
            TaskShortcutHelper.Reconciled(if (seen.size == 1) ReconcileOutcome.FAILED else ReconcileOutcome.APPLIED)
        }
        watcher.watch(flowOf(mapOf(4L to "A")))

        assertEquals(listOf(WatchState(null, afterRestore = true), WatchState(mapOf(4L to "A"), afterRestore = true)), seen)
        assertEquals(WatchState(mapOf(4L to "A"), afterRestore = false), watcher.state)
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
