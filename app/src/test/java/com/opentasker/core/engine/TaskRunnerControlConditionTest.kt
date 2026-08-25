package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The generic "Run only if" guard ([ActionSpec.condition], what a Tasker `<ConditionList>` imports
 * to) on flow-control actions. Non-control actions have always evaluated it in `runOne`, but the
 * control markers are dispatched through `stepControl` before that check, so an imported
 * "Stop If %x ~ y" stopped unconditionally: the condition survived in data and was ignored at
 * runtime. Tasker semantics throughout: an unmet guard skips the control action, it never aborts
 * the task; for block-opening markers the whole block is what gets skipped.
 */
class TaskRunnerControlConditionTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
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

    private fun ctrl(type: String, args: Map<String, String> = emptyMap(), condition: String? = null) =
        ActionSpec(type = type, args = args, condition = condition)

    private fun run(variables: VariableStore = VariableStore(), vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
        }

    // flow.stop — the imported "Stop If" case.

    @Test
    fun stopWithUnmetConditionFallsThroughInsteadOfStopping() {
        val report = run(
            VariableStore().apply { set("mode", "off") },
            record("before"),
            ctrl(FlowControl.STOP, condition = "%mode == on"),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("before", "after"), recorded)
        assertEquals(ActionTraceStatus.SKIPPED, report.traces[1].status)
    }

    @Test
    fun stopWithMetConditionStops() {
        val report = run(
            VariableStore().apply { set("mode", "on") },
            record("before"),
            ctrl(FlowControl.STOP, condition = "%mode == on"),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("before"), recorded)
    }

    @Test
    fun stopWithoutConditionStillStopsUnconditionally() {
        val report = run(
            VariableStore(),
            record("before"),
            ctrl(FlowControl.STOP),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("before"), recorded)
    }

    // flow.foreach — skipping the opener skips the whole loop block.

    @Test
    fun foreachWithUnmetConditionSkipsTheLoopBlock() {
        val variables = VariableStore().apply {
            setArray("xs", listOf("a", "b"))
            set("mode", "off")
        }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item"), condition = "%mode == on"),
            record("%item"),
            ctrl(FlowControl.ENDFOR),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun foreachWithMetConditionIterates() {
        val variables = VariableStore().apply {
            setArray("xs", listOf("a", "b"))
            set("mode", "on")
        }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item"), condition = "%mode == on"),
            record("%item"),
            ctrl(FlowControl.ENDFOR),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("a", "b", "after"), recorded)
    }

    // flow.endfor — a skipped End For does not jump back, so the loop exits early.

    @Test
    fun endforWithUnmetConditionExitsTheLoopEarly() {
        val variables = VariableStore().apply { setArray("xs", listOf("a", "b", "c")) }
        val report = run(
            variables,
            ctrl(FlowControl.FOREACH, mapOf("list" to "xs", "var" to "item")),
            record("%item"),
            ctrl(FlowControl.ENDFOR, condition = "%item != b"),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("a", "b", "after"), recorded)
    }

    // flow.try — skipping the opener skips the body and its handler.

    @Test
    fun tryWithUnmetConditionSkipsBodyAndCatch() {
        val report = run(
            VariableStore().apply { set("mode", "off") },
            ctrl(FlowControl.TRY, condition = "%mode == on"),
            record("body"),
            ctrl(FlowControl.CATCH),
            record("handler"),
            ctrl(FlowControl.ENDTRY),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun tryWithMetConditionRunsItsBody() {
        val report = run(
            VariableStore().apply { set("mode", "on") },
            ctrl(FlowControl.TRY, condition = "%mode == on"),
            record("body"),
            ctrl(FlowControl.CATCH),
            record("handler"),
            ctrl(FlowControl.ENDTRY),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("body", "after"), recorded)
    }

    // flow.if — args["condition"] is the if's own test; a distinct guard applies alongside it.

    @Test
    fun ifWithUnmetGuardTreatsBranchAsFalseEvenWhenTestIsTrue() {
        val report = run(
            VariableStore().apply { set("a", "1"); set("b", "0") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1"), condition = "%b == 1"),
            record("then"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun ifWithMetGuardAndMetTestRunsBranch() {
        val report = run(
            VariableStore().apply { set("a", "1"); set("b", "1") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1"), condition = "%b == 1"),
            record("then"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("then", "after"), recorded)
    }

    @Test
    fun ifWithGuardCopiedIntoArgsByImportBehavesAsSingleTest() {
        // The importer writes a flow.if ConditionList into both fields with identical text; the
        // one expression must gate the branch exactly once, not double-apply or skip-run the block.
        val report = run(
            VariableStore().apply { set("text", "hello") },
            ctrl(FlowControl.IF, mapOf("condition" to "%text is_set"), condition = "%text is_set"),
            record("then"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("then", "after"), recorded)
    }

    // flow.else — a guard on the else marker is Tasker's "Else If".

    @Test
    fun elseIfWithUnmetGuardSkipsTheElseBranch() {
        val report = run(
            VariableStore().apply { set("a", "0"); set("b", "0") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1")),
            record("then"),
            ctrl(FlowControl.ELSE, condition = "%b == 1"),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("after"), recorded)
    }

    @Test
    fun elseIfWithMetGuardRunsTheElseBranch() {
        val report = run(
            VariableStore().apply { set("a", "0"); set("b", "1") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1")),
            record("then"),
            ctrl(FlowControl.ELSE, condition = "%b == 1"),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("else", "after"), recorded)
    }

    @Test
    fun elseIfGuardIsIgnoredWhenTheIfBranchWasTaken() {
        // Falling out of a taken if branch crosses the else marker as an exit jump; the "Else If"
        // guard only decides whether the branch runs, never whether the block exits.
        val report = run(
            VariableStore().apply { set("a", "1"); set("b", "0") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1")),
            record("then"),
            ctrl(FlowControl.ELSE, condition = "%b == 1"),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("then", "after"), recorded)
    }

    @Test
    fun unguardedElseStillRunsWhenIfIsFalse() {
        val report = run(
            VariableStore().apply { set("a", "0") },
            ctrl(FlowControl.IF, mapOf("condition" to "%a == 1")),
            record("then"),
            ctrl(FlowControl.ELSE),
            record("else"),
            ctrl(FlowControl.ENDIF),
            record("after"),
        )
        assertTrue(report.success)
        assertEquals(listOf("else", "after"), recorded)
    }
}
