package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerSubTaskTest {

    /** Records into a shared variable so we can observe whether a sub-task ran. */
    private fun registerRecorderAction(id: String) {
        ActionRegistry.register(object : Action {
            override val id = id
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                val key = args["key"] ?: "RAN"
                ctx.variables.set(key, args["value"] ?: "true")
                return ActionResult.Success
            }
        })
    }

    private fun runner(variables: VariableStore, resolve: SubTaskResolver?) =
        TaskRunner(ActionContext(ContextWrapper(null), variables), resolveTask = resolve)

    @Test
    fun runsResolvedSubTask() = runBlocking {
        registerRecorderAction("test.sub.recorder")
        val subTask = Task(
            id = 42,
            name = "Toggle WiFi",
            actions = listOf(ActionSpec(type = "test.sub.recorder", args = mapOf("key" to "SUB_RAN"))),
        )
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "42" || ref == "Toggle WiFi" } }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Toggle WiFi")))),
        )
        assertTrue(report.success)
        assertEquals("true", variables.get("SUB_RAN"))
    }

    @Test
    fun passesInputVariablesAndReceivesGlobalOutput() = runBlocking {
        // Sub-task echoes an input variable into a global output variable.
        ActionRegistry.register(object : Action {
            override val id = "test.sub.echo"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("RESULT", ctx.variables.get("input").orEmpty())
                return ActionResult.Success
            }
        })
        val subTask = Task(name = "Echo", actions = listOf(ActionSpec(type = "test.sub.echo")))
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Echo" } }.run(
            Task(
                name = "Parent",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Echo", "input" to "hello")),
                ),
            ),
        )
        assertTrue(report.success)
        assertEquals("hello", variables.get("RESULT"))
    }

    @Test
    fun failsWhenSubTaskNotFound() = runBlocking {
        val report = runner(VariableStore()) { null }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "ghost")))),
        )
        assertFalse(report.success)
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("not found"))
    }

    @Test
    fun failsWhenNoResolverAvailable() = runBlocking {
        val report = runner(VariableStore(), resolve = null).run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "x")))),
        )
        assertFalse(report.success)
    }

    @Test
    fun failsWhenTaskReferenceMissing() = runBlocking {
        val report = runner(VariableStore()) { Task(name = "x", actions = emptyList()) }.run(
            Task(name = "Parent", actions = listOf(ActionSpec(type = "task.run"))),
        )
        assertFalse(report.success)
    }

    @Test
    fun boundsRecursionAtDepthLimitWithoutStackOverflow() = runBlocking {
        // A task that calls itself; the resolver always returns the same recursive task.
        val recursive = Task(
            id = 1,
            name = "Recursive",
            actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "Recursive"))),
        )
        val report = runner(VariableStore()) { ref -> recursive.takeIf { ref == "Recursive" } }.run(recursive)
        assertFalse(report.success)
        // The top-level action fails because somewhere below the depth limit was hit.
        assertTrue(report.results.any { it is ActionResult.Failure })
    }
}
