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

/**
 * An action switched off in the editor is walked past — ANY action, flow control included.
 *
 * ## Why the flag is not just another condition
 *
 * [ActionSpec.condition] belongs to the task's logic and is re-evaluated every run.
 * [ActionSpec.enabled] belongs to whoever is editing: it keeps the action's arguments, label and
 * place while taking it out of the run, which is the thing 白い熊 previously had to express by
 * deleting the action and hoping a mirror could put it back (2026-09-10).
 *
 * ## What it deliberately does NOT do
 *
 * It is not block-aware. Disabling a `flow.if` removes the TEST, not the block, so the body runs
 * unconditionally; disabling a `flow.else` lets both branches run. 白い熊 asked for exactly that —
 * *"It doesn't have to mean anything — I want to be able to disable any action; the propriety of
 * the full task is then on me"* — and these tests pin the behaviour down rather than defend it.
 *
 * ## What the engine still guarantees
 *
 * It cannot hang or corrupt. [FlowStructure] analyses every action including disabled ones, so the
 * `if`/`endif` pairing never shifts; an `endfor` reached without its loop fails with a named error
 * instead of spinning; and the flow step budget bounds the rest.
 */
class DisabledActionTest {

    private val recorded = mutableListOf<String>()

    @Before
    fun setUp() {
        recorded.clear()
        ActionRegistry.register(object : Action {
            override val id = "test.disabled.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args["v"].orEmpty()
                return ActionResult.Success
            }
        })
    }

    private fun record(value: String, enabled: Boolean = true) =
        ActionSpec(type = "test.disabled.record", args = mapOf("v" to value), enabled = enabled)

    private fun run(variables: VariableStore = VariableStore(), vararg actions: ActionSpec): TaskRunReport =
        runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables))
                .run(Task(name = "T", actions = actions.toList()))
        }

    @Test
    fun `a disabled action does not run and the rest of the task does`() {
        val report = run(VariableStore(), record("a"), record("b", enabled = false), record("c"))
        assertEquals(listOf("a", "c"), recorded)
        assertTrue("skipping is not failing", report.success)
    }

    /** And the run log says WHY, because "Skipped" alone already means a false condition. */
    @Test
    fun `the trace names the reason`() {
        val report = run(VariableStore(), record("a", enabled = false))
        assertEquals(ActionTraceStatus.SKIPPED, report.traces[0].status)
        assertEquals("Disabled", report.traces[0].message)
    }

    /** Every action off is a task that does nothing, not a task that fails. */
    @Test
    fun `a task of only disabled actions succeeds having done nothing`() {
        val report = run(VariableStore(), record("a", enabled = false), record("b", enabled = false))
        assertTrue(recorded.isEmpty())
        assertTrue(report.success)
    }

    /**
     * A disabled `flow.if` removes the TEST, so the body runs even though the condition is false.
     *
     * Not a block disable. This is the behaviour 白い熊 asked for, written down so nobody later
     * "fixes" it into something block-aware without being asked.
     */
    @Test
    fun `a disabled if marker takes its test out of the task`() {
        val report = run(
            VariableStore().apply { set("a", "0") },
            record("before"),
            ActionSpec(type = FlowControl.IF, args = mapOf("condition" to "%a == 1"), enabled = false),
            record("then"),
            ActionSpec(type = FlowControl.ENDIF),
            record("after"),
        )
        assertEquals("the test is gone, so the body ran", listOf("before", "then", "after"), recorded)
        assertTrue(report.success)
        assertEquals(ActionTraceStatus.SKIPPED, report.traces[1].status)
        assertEquals("Disabled", report.traces[1].message)
    }

    /** An enabled `if` with the same false test still keeps its body out — the flag is what changed. */
    @Test
    fun `the same block with the marker enabled still decides`() {
        run(
            VariableStore().apply { set("a", "0") },
            record("before"),
            ActionSpec(type = FlowControl.IF, args = mapOf("condition" to "%a == 1")),
            record("then"),
            ActionSpec(type = FlowControl.ENDIF),
            record("after"),
        )
        assertEquals(listOf("before", "after"), recorded)
    }

    /**
     * A disabled `flow.foreach` fails at its `end for` rather than looping forever.
     *
     * This is the guarantee that survives handing the author the rope: the task is wrong, and it
     * says so by name and stops, instead of spinning to the step budget.
     */
    @Test
    fun `a disabled loop opener fails legibly at its closing marker`() {
        val report = run(
            VariableStore().apply { set("items", "a,b,c") },
            record("before"),
            ActionSpec(
                type = FlowControl.FOREACH,
                args = mapOf("items" to "%items", "var" to "it"),
                enabled = false,
            ),
            record("body"),
            ActionSpec(type = FlowControl.ENDFOR),
            record("after"),
        )
        assertFalse("a loop with no opener is a broken task and must say so", report.success)
        assertTrue(
            "and it must name what is wrong, in the run log as well as the result",
            report.traces.any { it.message.contains("without an active loop") },
        )
        assertEquals("it must stop, not spin", listOf("before", "body"), recorded)
    }

    /**
     * A disabled TASK is skipped when another task calls it, and the caller carries on.
     *
     * `task.run` builds its own child runner instead of going back through `executeAndLogTask`, so
     * the gate there does not cover this path — and this is the path 起動完了 ⇨ 起動 uses to start
     * every project. A switch every other caller obeyed while the master startup task overrode it
     * would be worse than no switch.
     */
    @Test
    fun `a disabled sub-task is skipped and the caller continues`() {
        val target = Task(id = 42, name = "child", enabled = false, actions = listOf(record("child-ran")))
        val report = runBlocking {
            TaskRunner(
                ActionContext(ContextWrapper(null), VariableStore()),
                resolveTask = { ref -> target.takeIf { ref == "child" || ref == "42" } },
            ).run(
                Task(
                    name = "parent",
                    actions = listOf(
                        record("before"),
                        ActionSpec(type = "task.run", args = mapOf("task" to "child")),
                        record("after"),
                    ),
                ),
            )
        }
        assertEquals("the child must not have run", listOf("before", "after"), recorded)
        assertTrue("a switched-off child is not a failed parent", report.success)
        assertEquals(ActionTraceStatus.SKIPPED, report.traces[1].status)
        assertTrue(report.traces[1].message.contains("disabled"))
    }

    /** The same child, switched on, proves the resolver and the call really work. */
    @Test
    fun `an enabled sub-task still runs`() {
        val target = Task(id = 42, name = "child", actions = listOf(record("child-ran")))
        runBlocking {
            TaskRunner(
                ActionContext(ContextWrapper(null), VariableStore()),
                resolveTask = { ref -> target.takeIf { ref == "child" || ref == "42" } },
            ).run(
                Task(
                    name = "parent",
                    actions = listOf(record("before"), ActionSpec(type = "task.run", args = mapOf("task" to "child"))),
                ),
            )
        }
        assertEquals(listOf("before", "child-ran"), recorded)
    }

    /** An action stored before the flag existed decodes as ENABLED, not as off. */
    @Test
    fun `actions written before the flag existed default to on`() {
        val legacy = """[{"type":"test.disabled.record","args":{"v":"x"}}]"""
        val decoded = com.opentasker.core.storage.StorageJson
            .decodeFromString<List<ActionSpec>>(legacy)
        assertEquals(1, decoded.size)
        assertTrue("a missing flag must never read as disabled", decoded[0].enabled)
        assertFalse(decoded[0].continueOnError)
    }
}
