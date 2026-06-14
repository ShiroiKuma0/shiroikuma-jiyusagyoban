package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskRunnerFlowControlTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        // Records the (expanded) "v" argument so we can observe which body actions ran and loop values.
        ActionRegistry.register(object : Action {
            override val id = "test.flow.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
    }

    private fun record(value: String) = ActionSpec(type = "test.flow.record", args = mapOf("v" to value))
    private fun ctrl(type: String, args: Map<String, String> = emptyMap()) = ActionSpec(type = type, args = args)

    private fun run(variables: VariableStore = VariableStore(), vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
        }

    @Test
    fun ifTrueRunsBodyAndSkipsElse() {
        val report = run(
            VariableStore().apply { set("mode", "on") },
            ctrl(FlowControl.IF, mapOf("condition" to "%mode == on")),
            record("then"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("then", "after"), recorded)
    }

    @Test
    fun ifFalseRunsElseBranch() {
        val report = run(
            VariableStore().apply { set("mode", "off") },
            ctrl(FlowControl.IF, mapOf("condition" to "%mode == on")),
            record("then"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("else", "after"), recorded)
    }

    @Test
    fun ifFalseWithoutElseSkipsBody() {
        val report = run(
            VariableStore().apply { set("mode", "off") },
            ctrl(FlowControl.IF, mapOf("condition" to "%mode == on")),
            record("then"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun nestedIfEvaluatesInnerBlock() {
        val report = run(
            VariableStore().apply { set("a", "1"); set("b", "1") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1")),
            ctrl(FlowControl.IF, mapOf("condition" to "%b == 1")),
            record("inner"),
            ctrl(FlowControl.ENDIF),
            record("outer"),
            ctrl(FlowControl.ENDIF),
        )
        assertTrue(report.success)
        assertEquals(listOf("inner", "outer"), recorded)
    }

    @Test
    fun foreachIteratesArrayItems() {
        val variables = VariableStore().apply { setArray("xs", listOf("a", "b", "c")) }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            record("%item"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )
        assertTrue(report.success)
        assertEquals(listOf("a", "b", "c", "done"), recorded)
    }

    @Test
    fun foreachEmptyArraySkipsBody() {
        val variables = VariableStore().apply { setArray("xs", emptyList()) }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            record("%item"),
            ctrl(FlowControl.ENDFOR),
            record("done"),
        )
        assertTrue(report.success)
        assertEquals(listOf("done"), recorded)
    }

    @Test
    fun stopHaltsRemainingActions() {
        val report = run(
            VariableStore(),
            record("first"),
            ctrl(FlowControl.STOP),
            record("never"),
        )
        assertTrue(report.success)
        assertEquals(listOf("first"), recorded)
    }

    @Test
    fun ifInsideForeachFiltersItems() {
        val variables = VariableStore().apply { setArray("xs", listOf("a", "skip", "b")) }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            ctrl(FlowControl.IF, mapOf("condition" to "%item != skip")),
            record("%item"),
            ctrl(FlowControl.ENDIF),
            ctrl(FlowControl.ENDFOR),
        )
        assertTrue(report.success)
        assertEquals(listOf("a", "b"), recorded)
    }

    @Test
    fun unbalancedMarkersFailHonestly() {
        val report = run(
            VariableStore(),
            ctrl(FlowControl.IF, mapOf("condition" to "true")),
            record("body"),
            // missing endif
        )
        assertFalse(report.success)
        assertTrue(recorded.isEmpty())
    }
}
