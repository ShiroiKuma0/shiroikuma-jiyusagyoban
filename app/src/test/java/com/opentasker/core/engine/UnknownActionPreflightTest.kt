package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownActionPreflightTest {
    @Test
    fun unknownActionFailsBeforeEarlierSideEffectsRun() = runBlocking {
        val executions = AtomicInteger(0)
        val sideEffectActionId = "test.preflight.side-effect"
        ActionRegistry.register(
            object : Action {
                override val id = sideEffectActionId
                override val category = ActionCategory.SYSTEM

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    executions.incrementAndGet()
                    return ActionResult.Success
                }
            },
        )
        val report = TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(
            Task(
                name = "Unknown action",
                actions = listOf(
                    ActionSpec(type = sideEffectActionId),
                    ActionSpec(type = "missing.unclassified.action"),
                ),
            ),
        )

        assertFalse(report.success)
        assertEquals(0, executions.get())
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("unknown unclassified"))
    }

    @Test
    fun knownUnsupportedActionFailsBeforeEarlierSideEffectsRun() = runBlocking {
        val executions = AtomicInteger(0)
        val sideEffectActionId = "test.preflight.before-unsupported"
        ActionRegistry.register(
            object : Action {
                override val id = sideEffectActionId
                override val category = ActionCategory.SYSTEM

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    executions.incrementAndGet()
                    return ActionResult.Success
                }
            },
        )

        val report = TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(
            Task(
                name = "Unsupported action",
                actions = listOf(
                    ActionSpec(type = sideEffectActionId),
                    ActionSpec(type = "tasker.unsupported"),
                ),
            ),
        )

        assertFalse(report.success)
        assertEquals(0, executions.get())
        assertTrue((report.results.single() as ActionResult.Failure).message.contains("unsupported actions"))
    }
}
