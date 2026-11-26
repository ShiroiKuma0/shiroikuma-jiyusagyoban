package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.engine.variables.BuiltInVariables
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * `%TRUN` lists a task another task runs as well as one a profile runs, so a sub-task shows there
 * while it's going and drops off when it ends.
 */
class SubTaskRunningListTest {
    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        BuiltInVariables.resetForTests()
        ActiveExecutionRegistry.reset()
    }

    @After
    fun tearDown() {
        BuiltInVariables.resetForTests()
        ActiveExecutionRegistry.reset()
    }

    private fun set(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun runTask(task: String) = ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to task))

    private fun run(vararg tasks: Task): VariableStore = VariableStore().also { store ->
        val byName = tasks.associateBy { it.name }
        runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store), resolveTask = { byName[it] }).run(tasks.first()) }
    }

    @Test
    fun aSubTaskIsListedWhileItRuns() {
        val store = run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Middle"), set("AFTER", "[%TRUN]"))),
            Task(id = 2, name = "Middle", actions = listOf(runTask("Inner"))),
            Task(id = 3, name = "Inner", actions = listOf(set("SEEN", "%TRUN"))),
        )

        assertEquals(",Middle,Inner,", store.get("SEEN"))
        assertEquals("[%TRUN]", store.get("AFTER"))
        assertNull(BuiltInVariables.value("TRUN"))
    }

    @Test
    fun aSubTaskThatFailsStillDropsOff() {
        run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Broken").copy(continueOnError = true))),
            Task(id = 2, name = "Broken", actions = listOf(ActionSpec(type = "no.such.action"))),
        )

        assertNull(BuiltInVariables.value("TRUN"))
    }

    @Test
    fun aSubTaskCountsAsRunningForItsCardUntilItEnds() {
        val seen = mutableListOf<List<Long>>()
        ActionRegistry.register(object : Action {
            override val id = "test.nested_ids"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                seen += ActiveExecutionRegistry.nestedTaskIds.value
                return ActionResult.Success
            }
        })
        val capture = ActionSpec(type = "test.nested_ids")

        run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Middle"), capture)),
            Task(id = 2, name = "Middle", actions = listOf(runTask("Inner"))),
            Task(id = 3, name = "Inner", actions = listOf(capture)),
        )

        assertEquals(listOf(listOf(2L, 3L), emptyList<Long>()), seen)
    }

    @Test
    fun aSubTaskThatFailsOrIsCancelledStopsCountingAsRunning() {
        val seen = mutableListOf<List<Long>>()
        ActionRegistry.register(object : Action {
            override val id = "test.nested_ids"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                seen += ActiveExecutionRegistry.nestedTaskIds.value
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.fail"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult = ActionResult.Failure("broken")
        })
        ActionRegistry.register(object : Action {
            override val id = "test.cancel"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult = throw CancellationException("stopped")
        })
        val capture = ActionSpec(type = "test.nested_ids")

        run(
            Task(id = 1, name = "Outer", actions = listOf(runTask("Broken").copy(continueOnError = true), capture)),
            Task(id = 2, name = "Broken", actions = listOf(capture, ActionSpec(type = "test.fail"))),
        )
        assertEquals("listed while it ran, gone once it failed", listOf(listOf(2L), emptyList<Long>()), seen)

        seen.clear()
        // A cancellation leaves the run by an exception, past the return a failure takes.
        assertThrows(CancellationException::class.java) {
            run(
                Task(id = 1, name = "Outer", actions = listOf(runTask("Stopped"))),
                Task(id = 3, name = "Stopped", actions = listOf(capture, ActionSpec(type = "test.cancel"))),
            )
        }
        assertEquals(listOf(listOf(3L)), seen)
        assertEquals(emptyList<Long>(), ActiveExecutionRegistry.nestedTaskIds.value)
    }
}
