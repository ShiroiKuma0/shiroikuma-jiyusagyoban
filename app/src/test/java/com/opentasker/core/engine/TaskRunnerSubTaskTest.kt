package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun passesInputParameterAndReceivesGlobalOutput() = runBlocking {
        // Sub-task echoes a named parameter (param:input) into a global output variable.
        ActionRegistry.register(object : Action {
            override val id = "test.sub.echo"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("RESULT", ctx.parameters["input"].orEmpty())
                return ActionResult.Success
            }
        })
        val subTask = Task(name = "Echo", actions = listOf(ActionSpec(type = "test.sub.echo")))
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Echo" } }.run(
            Task(
                name = "Parent",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Echo", "param:input" to "hello")),
                ),
            ),
        )
        assertTrue(report.success)
        assertEquals("hello", variables.get("RESULT"))
    }

    @Test
    fun subTaskInputVariablesDoNotLeakIntoParentScope() = runBlocking {
        registerRecorderAction("test.sub.recorder")
        // The parent records whatever it currently sees for the sub-task input name `input`
        // into a global AFTER the sub-task returns; a leak would surface the child's value.
        ActionRegistry.register(object : Action {
            override val id = "test.parent.capture"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.set("PARENT_SAW", ctx.variables.get("input").orEmpty())
                return ActionResult.Success
            }
        })
        val subTask = Task(name = "Echo", actions = listOf(ActionSpec(type = "test.sub.recorder", args = mapOf("key" to "SUB_RAN"))))
        val variables = VariableStore()
        val report = runner(variables) { ref -> subTask.takeIf { ref == "Echo" } }.run(
            Task(
                name = "Parent",
                actions = listOf(
                    ActionSpec(type = "task.run", args = mapOf("task" to "Echo", "input" to "child-only")),
                    ActionSpec(type = "test.parent.capture"),
                ),
            ),
        )
        assertTrue(report.success)
        assertEquals("true", variables.get("SUB_RAN"))
        // The child's lowercase input must not survive into the parent's later actions.
        assertEquals("", variables.get("PARENT_SAW"))
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

    @Test
    fun nestedTaskRunUsesTheTargetCollisionPolicy() = runBlocking {
        registerRecorderAction("test.sub.collision.recorder")
        val coordinator = TaskCollisionCoordinator()
        val child = Task(
            id = 42,
            name = "Child",
            collisionMode = CollisionMode.ABORT_NEW,
            actions = listOf(ActionSpec(type = "test.sub.collision.recorder", args = mapOf("key" to "CHILD_RAN"))),
        )
        val activeStarted = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val active = async {
            coordinator.execute(child) {
                activeStarted.complete(Unit)
                releaseActive.await()
            }
        }
        activeStarted.await()
        val variables = VariableStore()
        val report = TaskRunner(
            ctx = ActionContext(ContextWrapper(null), variables),
            resolveTask = { child },
            collisionCoordinator = coordinator,
            executionChain = setOf(7L),
        ).run(
            Task(
                id = 7,
                name = "Parent",
                actions = listOf(ActionSpec(type = "task.run", args = mapOf("task" to "42"))),
            ),
        )

        assertFalse(report.success)
        assertEquals(null, variables.get("CHILD_RAN"))
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("Abort new"))
        releaseActive.complete(Unit)
        active.await()
        Unit
    }
}
