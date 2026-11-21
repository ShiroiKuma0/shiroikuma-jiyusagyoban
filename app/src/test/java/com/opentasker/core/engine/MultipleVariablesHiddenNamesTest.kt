package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A names list that reads as holding a secret can carry a typed value as a bare word, which a run
 * then sets as a variable of that name, so the run's change list mustn't print it.
 */
class MultipleVariablesHiddenNamesTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun setMultiple(names: String, values: String) =
        ActionSpec(type = "var.set_multiple", args = mapOf("names" to names, "values" to values))

    private fun run(step: ActionSpec): Pair<String, ActionExecutionTrace> {
        val store = VariableStore().apply { set("Pin", "1234", sensitive = true) }
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, logger = {})).run(Task(name = "T", actions = listOf(step)))
        }
        return runLogMessage(source = "manual", traces = report.traces) to report.traces.single()
    }

    @Test
    fun aBareWordInASecretLookingListIsOnlyCounted() {
        val (serialized, trace) = run(setMultiple("%api_token\nhunter2", "a\nb"))
        assertFalse(serialized, "hunter2" in serialized)
        assertFalse(trace.toString(), "hunter2" in trace.toString())
        // The %-written name still shows, with its value hidden.
        val token = trace.variableChanges.single { it.name == "api_token" }
        assertTrue(token.sensitive)
        val counted = trace.variableChanges.single { it.name != "api_token" }
        assertTrue(counted.sensitive)
        assertTrue(counted.name, counted.name.startsWith("1 "))
    }

    @Test
    fun aListOfPlainNamesShowsEveryName() {
        val (_, trace) = run(setMultiple("%api_token\n%color", "a\nb"))
        assertEquals(setOf("api_token", "color"), trace.variableChanges.map { it.name }.toSet())
        assertEquals("b", trace.variableChanges.single { it.name == "color" }.value)
    }

    @Test
    fun aBareWordInAnOrdinaryListStillShows() {
        val (_, trace) = run(setMultiple("%color\nsize", "red\n2"))
        assertEquals(setOf("color", "size"), trace.variableChanges.map { it.name }.toSet())
    }

    @Test
    fun preflightHidesTheSameNames() {
        val step = PreflightRunner.preflightTask(Task(id = 1, name = "T", actions = listOf(setMultiple("%api_token\nhunter2", "a\nb"))))
            .tasks.single().steps.single()
        assertEquals(ActionArgumentSensitivity.REDACTED, step.expandedArguments["names"])
        assertFalse(step.toString(), "hunter2" in step.toString())
    }
}
