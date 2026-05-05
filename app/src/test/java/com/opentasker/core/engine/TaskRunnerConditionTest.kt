package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRunnerConditionTest {
    @Test
    fun skipsActionWhenConditionEvaluatesFalse() = runBlocking {
        var ran = false
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.skip"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    ran = true
                    return ActionResult.Success
                }
            }
        )

        val variables = VariableStore().apply { set("mode", "off") }
        val report = TaskRunner(ActionContext(ContextWrapper(null), variables)).run(
            Task(
                name = "Conditional",
                actions = listOf(
                    ActionSpec(
                        type = "test.condition.skip",
                        condition = "%mode == on",
                    )
                ),
            )
        )

        assertFalse(ran)
        assertTrue(report.success)
        assertTrue(report.results.single() is ActionResult.Skip)
    }

    @Test
    fun propagatesCoroutineCancellationFromActions() = runBlocking {
        ActionRegistry.register(
            object : Action {
                override val id = "test.condition.cancel"
                override val category = ActionCategory.FLOW

                override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                    throw CancellationException("cancelled")
                }
            }
        )

        val result = runCatching {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore())).run(
                Task(
                    name = "Cancellation",
                    actions = listOf(ActionSpec(type = "test.condition.cancel")),
                )
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
