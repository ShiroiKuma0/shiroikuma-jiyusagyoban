package com.opentasker.core.engine

import com.opentasker.ProductionSources
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleActionRunTest {

    private val task = Task(
        id = 7L,
        name = "Evening wind down",
        collisionMode = CollisionMode.ABORT_EXISTING,
        projectId = 3L,
        actions = listOf(
            ActionSpec(id = 1L, type = "var.set", label = "Remember volume", args = mapOf("name" to "vol")),
            ActionSpec(id = 2L, type = FlowControl.IF, args = mapOf("condition" to "%vol > 5")),
            ActionSpec(id = 3L, type = "http.request", args = mapOf("url" to "https://example.test")),
            ActionSpec(id = 4L, type = FlowControl.ENDIF),
        ),
    )

    @Test
    fun `a plain action runs in isolation and keeps its task identity`() {
        val single = SingleActionRun.taskFor(task, index = 0)

        assertEquals(listOf(task.actions[0]), single?.actions)
        // Same id, collision policy and project: a single-action run and a full run of the same
        // task share task-scoped variables, so they must also share the collision policy.
        assertEquals(task.id, single?.id)
        assertEquals(task.collisionMode, single?.collisionMode)
        assertEquals(task.projectId, single?.projectId)
        assertEquals(task.name, single?.name)
    }

    @Test
    fun `a flow-control marker is never runnable on its own`() {
        FlowControl.ALL.forEach { type ->
            assertFalse(type, SingleActionRun.isRunnableAlone(ActionSpec(type = type)))
        }
        assertNull("an if marker must not produce a runnable task", SingleActionRun.taskFor(task, index = 1))
        assertNull("an end if marker must not produce a runnable task", SingleActionRun.taskFor(task, index = 3))
    }

    @Test
    fun `an out of range index produces nothing rather than throwing`() {
        assertNull(SingleActionRun.taskFor(task, index = -1))
        assertNull(SingleActionRun.taskFor(task, index = task.actions.size))
    }

    @Test
    fun `the run log can tell a single-action run apart from a manual run`() {
        val source = SingleActionRun.sourceFor("Remember volume")

        val classified = RunLogSource.classify(source)
        assertEquals(RunLogSource.SINGLE_ACTION, classified.key)
        assertEquals("Remember volume", classified.label)
        // Still a manual run as far as the execution ledger is concerned; only the scope differs.
        assertEquals(ExecutionProducer.MANUAL, ExecutionProducer.fromSource(source))
    }

    @Test
    fun `an unlabelled action still classifies as a single-action run`() {
        val classified = RunLogSource.classify(SingleActionRun.sourceFor("   "))

        assertEquals(RunLogSource.SINGLE_ACTION, classified.key)
        assertNull(classified.label)
    }

    @Test
    fun `a manual run of a whole task is not misread as a single-action run`() {
        assertEquals(RunLogSource.MANUAL_RUN, RunLogSource.classify("Manual run").key)
    }

    /**
     * The exclusion has to hold in the menu as well as in the helper. Offering the option and
     * failing later would be worse than not offering it, and the helper alone cannot prove the
     * menu asks.
     */
    @Test
    fun `the action menu offers the run option only for runnable actions`() {
        val guarded = ProductionSources.block(
            "com/opentasker/ui/screens/ActiveAutomationLists.kt",
            "if (SingleActionRun.isRunnableAlone(action)) {",
            // The fork's action menu is its own (Clone/Copy/Cut/Delete on the whole selection), so the
            // slice ends at ITS delete item rather than upstream's multi-line, resource-backed one.
            "DropdownMenuItem(text = { Text(\"Clone\") }, onClick = onClone)",
        )
        assertTrue("the run option must sit behind the runnable check", "R.string.action_run_alone" in guarded)
        assertTrue("the run option must call back", "onRun()" in guarded)
    }

    @Test
    fun `running one action goes through the same execution path as a whole task`() {
        val body = ProductionSources.block(
            "com/opentasker/ui/screens/ActiveAutomationViewModel.kt",
            "fun runActionNow(",
            "fun replayHeldRun(",
        )

        assertTrue("must refuse a stale or flow-control index", "SingleActionRun.taskFor(task, index) ?: return@launch" in body)
        assertTrue("must use the live admission controller", "ExecutionAdmissionRegistry.current(appContext)" in body)
        assertTrue("must log through the normal execution path", "executeAndLogTask(" in body)
        assertTrue("must label the run-log row", "SingleActionRun.sourceFor(label)" in body)
        assertTrue("must share the whole-task busy guard", "_runActionBusy" in body)
    }
}
