package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-step variable capture: what a task actually set, recovered from a finished run.
 *
 * Traces already showed what went *into* each action; without this, nothing showed what came out,
 * and the durable global runtime was only visible after the fact in the Variables vault.
 */
class VariableChangeInspectionTest {

    @Before
    fun setUp() {
        ActionRegistry.register(object : Action {
            override val id = "test.var.write"
            override val category = ActionCategory.VARIABLE
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                args.forEach { (name, value) -> ctx.variables.set(name, value) }
                return ActionResult.Success
            }
        })
        ActionRegistry.register(object : Action {
            override val id = "test.var.none"
            override val category = ActionCategory.VARIABLE
            override suspend fun run(ctx: ActionContext, args: Map<String, String>) = ActionResult.Success
        })
    }

    private fun run(variables: VariableStore, vararg actions: ActionSpec): TaskRunReport = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), variables)).run(Task(name = "T", actions = actions.toList()))
    }

    @Test
    fun aStepRecordsTheTaskAndGlobalVariablesItWrote() {
        val report = run(
            VariableStore(),
            ActionSpec(type = "test.var.write", args = mapOf("greeting" to "hello", "COUNT" to "1")),
        )

        val changes = report.traces.single().variableChanges
        assertEquals(
            listOf(
                VariableChangeScope.TASK to "greeting",
                VariableChangeScope.GLOBAL to "COUNT",
            ),
            changes.map { it.scope to it.name },
        )
        assertTrue("a first write is an addition", changes.all { it.added })
        assertEquals(listOf("hello", "1"), changes.map { it.value })
    }

    @Test
    fun aRewriteIsReportedAsAnUpdateAndAnUntouchedVariableIsNotReportedAtAll() {
        val report = run(
            VariableStore(),
            ActionSpec(type = "test.var.write", args = mapOf("mode" to "on")),
            ActionSpec(type = "test.var.write", args = mapOf("mode" to "off")),
            ActionSpec(type = "test.var.write", args = mapOf("mode" to "off")),
        )

        assertTrue(report.traces[0].variableChanges.single().added)
        val second = report.traces[1].variableChanges.single()
        assertFalse(second.added)
        assertEquals("off", second.value)
        // Writing the same value again is not a change and must not pad the run log.
        assertTrue(report.traces[2].variableChanges.isEmpty())
    }

    @Test
    fun aStepThatWritesNothingRecordsNothing() {
        val report = run(VariableStore(), ActionSpec(type = "test.var.none"))
        assertTrue(report.traces.single().variableChanges.isEmpty())
    }

    @Test
    fun theRunLogRoundTripsVariableChangesBackOntoTheirStep() {
        val report = run(
            VariableStore(),
            ActionSpec(type = "test.var.write", args = mapOf("greeting" to "hello", "COUNT" to "1")),
        )

        val diagnostics = runLogMessage(source = "manual", traces = report.traces).toRunLogDiagnostics()
        val recovered = diagnostics.traces.single().variableChanges
        assertEquals(listOf("greeting", "COUNT"), recovered.map { it.name })
        assertEquals(listOf("task", "global"), recovered.map { it.scope })
        assertEquals(listOf("hello", "1"), recovered.map { it.value })
        assertTrue(recovered.all { it.added })
        assertTrue("variable lines must not leak into unparsed details", diagnostics.detailLines.isEmpty())
    }

    @Test
    fun aSecretDerivedValueIsRedactedInTheRunLogRatherThanStored() {
        val variables = VariableStore()
        variables.set("API_TOKEN", "s3cr3t-value", sensitive = true)
        val report = run(variables, ActionSpec(type = "test.var.write", args = mapOf("API_TOKEN" to "rotated-secret")))

        val serialized = runLogMessage(source = "manual", traces = report.traces)
        assertFalse("the raw secret must never reach the run log", "rotated-secret" in serialized)
        assertTrue("<redacted>" in serialized)

        val recovered = serialized.toRunLogDiagnostics().traces.single().variableChanges.single()
        assertEquals("API_TOKEN", recovered.name)
        assertEquals("<redacted>", recovered.value)
    }

    @Test
    fun arrayWritesAreReportedInTheirOwnScope() {
        ActionRegistry.register(object : Action {
            override val id = "test.var.array"
            override val category = ActionCategory.VARIABLE
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                ctx.variables.setArray("parts", listOf("a", "b", "c"))
                return ActionResult.Success
            }
        })

        val report = run(VariableStore(), ActionSpec(type = "test.var.array"))
        val arrayChange = report.traces.single().variableChanges.single { it.scope == VariableChangeScope.ARRAY }
        assertEquals("parts", arrayChange.name)
        assertEquals("a, b, c", arrayChange.value)
    }

    @Test
    fun theDiffIgnoresUnchangedEntriesAcrossEveryScope() {
        val before = TemplateScope(
            global = mapOf("A" to "1"),
            task = mapOf("b" to "2"),
            arrays = mapOf("c" to listOf("x")),
        )
        assertTrue(variableChangesBetween(before, before).isEmpty())

        val after = before.copy(
            global = mapOf("A" to "9"),
            task = mapOf("b" to "2", "d" to "4"),
            arrays = mapOf("c" to listOf("x", "y")),
        )
        assertEquals(
            listOf("d" to true, "A" to false, "c" to false),
            variableChangesBetween(before, after).map { it.name to it.added },
        )
    }
}
