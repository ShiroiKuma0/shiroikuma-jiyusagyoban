package com.opentasker.ui

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating bar offers a way back, and the way back puts things where they were.
 *
 * 白い熊 deleted a 58-action task by mistake on 2026-09-10. The only route back was a workspace
 * mirror that happened to be three days old and happened to be current for that project; had it not
 * been, the "restore" would have quietly reinstated an older task and reported success. Every
 * assertion here is a piece of the answer to that.
 *
 * Source gates: the behaviour needs a real Room database and a composition, and what has to hold is
 * a property of the design rather than of one call.
 */
class UndoContractTest {

    private val vm by lazy {
        ProductionSources.read("com/opentasker/ui/screens/ActiveAutomationViewModel.kt")
    }

    /** The bar carries a token, not a lambda, so it cannot outlive the view model it came from. */
    @Test
    fun `the message channel carries an undo token`() {
        val msg = ProductionSources.read("com/opentasker/ui/components/UiMessage.kt")
        assertTrue(msg.contains("val undoToken: String? = null"))
        assertTrue("the channel must carry the message object", vm.contains("Channel<UiMessage>"))
    }

    /**
     * A bar with an Undo waits indefinitely and carries its own ✕.
     *
     * Material's longest fixed duration is about ten seconds — not long enough to notice a mistake,
     * read what it was and decide.
     */
    @Test
    fun `an undo bar does not time out`() {
        val ui = ProductionSources.block(
            "com/opentasker/ui/screens/ActiveAutomationUi.kt",
            "viewModel.messages.collect",
            "var showMoreDestinations",
        )
        assertTrue(ui.contains("SnackbarDuration.Indefinite"))
        assertTrue("and it must be dismissible by hand", ui.contains("withDismissAction = message.undoToken != null"))
        assertTrue("pressing the pill must reach the view model", ui.contains("viewModel.undo(it)"))
        val bar = ProductionSources.read("com/opentasker/ui/components/ThemedSnackbar.kt")
        assertTrue("the pill must be drawn", bar.contains("data.performAction()"))
        assertTrue("and the close button", bar.contains("data.dismiss()"))
    }

    /**
     * A restored task keeps its ORIGINAL id.
     *
     * Run on start, Run on exit, widget bindings and a profile's enter/exit task all point at tasks
     * by number. A restore that took a fresh id would look successful and leave every one of them
     * pointing at nothing — the same fault as [com.opentasker.core.transfer.TaskListPrefsByNameTest],
     * arrived at from the other side.
     */
    @Test
    fun `a restored task goes back under its own id`() {
        val body = ProductionSources.block(
            "com/opentasker/ui/screens/ActiveAutomationViewModel.kt",
            "private suspend fun restoreTasks(",
            "private suspend fun restoreProfiles(",
        )
        assertTrue("the row must go back as it was", body.contains("db.taskDao().insert(row)"))
        assertTrue(
            "and only take a new id when the old one has been claimed",
            body.contains("if (db.taskDao().getById(row.id) == null)"),
        )
        assertTrue(
            "a renumbered restore must say so rather than report a clean one",
            body.contains("had to be renumbered"),
        )
    }

    /** A deleted task's icon is a file, and a file cannot come back from a row snapshot. */
    @Test
    fun `an icon is held while its undo is on offer`() {
        assertTrue("the icon must be parked, not deleted", vm.contains("undoIcons[row.id] = task.iconPath"))
        assertTrue("and swept when the offer lapses", vm.contains("forgetUndo(eldest.value.taskIds)"))
        assertTrue("a restored task keeps its icon", vm.contains("entities.forEach { undoIcons.remove(it.id) }"))
    }

    /** A token is spent once: a bar that lingers must not insert the row twice. */
    @Test
    fun `an undo can only be taken once`() {
        assertTrue(vm.contains("val pending = pendingUndos.remove(token)"))
        assertTrue(
            "and a spent token must answer rather than do nothing",
            vm.contains("That undo is no longer available"),
        )
    }

    /** Every tier is wired: action, task, profile, scene, group, project. */
    @Test
    fun `all four tiers offer an undo`() {
        assertTrue("actions", vm.contains("offer { restoreTaskActions("))
        assertTrue("contexts", vm.contains("offer { restoreProfileContexts("))
        assertTrue("tasks", vm.contains("restoreTasks(listOf(row))"))
        assertTrue("profiles", vm.contains("offer { restoreProfiles(listOf(row)) }"))
        assertTrue("scenes", vm.contains("offer { restoreScenes(listOf(row)) }"))
        assertTrue("groups", vm.contains("offer { restoreGroup(group, members, children) }"))
        assertTrue("projects", vm.contains("db.projectDao().insert(undoProject)"))
    }

    /** A failed operation must not leave an offer to reverse something that never happened. */
    @Test
    fun `a failed operation withdraws its offer`() {
        assertTrue(vm.contains("token?.let { t -> pendingUndos.remove(t) }"))
    }

    /** The deletion itself is immediate — the engine reads this database live. */
    @Test
    fun `nothing is deferred`() {
        assertFalse(
            "a delete must never be postponed until the bar closes",
            vm.contains("delayDelete") || vm.contains("pendingDeletion"),
        )
    }
}
