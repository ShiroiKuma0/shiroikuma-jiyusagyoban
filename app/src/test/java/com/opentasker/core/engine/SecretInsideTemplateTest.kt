package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A secret read inside `{{ }}` becomes the template's own text, which its warnings quote. */
class SecretInsideTemplateTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun setColor(value: String) = ActionSpec(type = "var.set", args = mapOf("name" to "%color", "value" to value))

    private fun runLogTrace(value: String): Pair<String, ActionExecutionTrace> {
        val store = VariableStore().apply { set("Token", "abc123", sensitive = true) }
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, logger = {})).run(Task(name = "T", actions = listOf(setColor(value))))
        }
        return runLogMessage(source = "manual", traces = report.traces) to report.traces.single()
    }

    private fun preflightWarnings(value: String) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = listOf(setColor(value))),
        inputs = PreflightInputs(globalVariables = mapOf("Token" to "abc123"), secretGlobalNames = setOf("Token")),
    ).tasks.single().steps.single().warnings

    @Test
    fun aSecretReadInsideATemplateStaysOutOfTheRunLog() {
        val (serialized, trace) = runLogTrace("{{ %Token }}")
        assertFalse(serialized, "abc123" in serialized)
        assertFalse(trace.toString(), "abc123" in trace.toString())
    }

    @Test
    fun aSecretReadOutsideATemplateKeepsItsWarningsInTheRunLog() {
        val (_, trace) = runLogTrace("%Token {{ hunter2 }}")
        assertTrue(trace.toString(), trace.argumentExpansions.flatMap { it.warnings }.any { "hunter2" in it })
    }

    @Test
    fun aSecretReadInsideATemplateStaysOutOfPreflight() {
        val warnings = preflightWarnings("{{ %Token }}")
        assertTrue(warnings.toString(), warnings.none { "abc123" in it })
        assertTrue(warnings.toString(), "value: ${ActionArgumentSensitivity.REDACTED}" in warnings)
    }

    @Test
    fun aSecretReadOutsideATemplateKeepsItsWarningsInPreflight() {
        assertTrue(preflightWarnings("%Token {{ hunter2 }}").any { "hunter2" in it })
    }

    private fun preflightStepWarnings(step: ActionSpec) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = listOf(step)),
        inputs = PreflightInputs(globalVariables = mapOf("Token" to "abc123"), secretGlobalNames = setOf("Token")),
    ).tasks.single().steps.single().warnings

    // Line lists expand one line at a time. The run log hides every one of them (headers and query by
    // name, Send Intent's extras by declaration), but Preflight keeps their warnings.
    private val intentWithExtras = ActionSpec(
        type = "intent.launch",
        args = mapOf("action" to "com.example.PING", "extras" to "mode=quiet\nlabel={{ %Token }}"),
    )

    @Test
    fun aSecretReadInsideATemplateOnOneLineOfAListStaysOutOfPreflight() {
        val headers = ActionSpec(
            type = "http.request",
            args = mapOf("url" to "https://example.com", "method" to "GET", "headers" to "Accept: text/plain\nX-Label: {{ %Token }}"),
        )
        val warnings = preflightStepWarnings(headers)
        assertTrue(warnings.toString(), "headers: ${ActionArgumentSensitivity.REDACTED}" in warnings)
        assertTrue(warnings.toString(), warnings.none { "abc123" in it })
        assertTrue(preflightStepWarnings(intentWithExtras).none { "abc123" in it })
    }

    @Test
    fun aSecretReadInsideATemplateInPluginSettingsStaysOutOfPreflight() {
        val plugin = ActionSpec(
            type = "plugin.locale.fire",
            args = mapOf(
                "packageName" to "com.example.plugin",
                "receiverClass" to "com.example.plugin.FireReceiver",
                "bundleJson" to """{"text":"{{ %Token }}"}""",
            ),
        )
        val warnings = preflightStepWarnings(plugin)
        assertTrue(warnings.toString(), "bundleJson: ${ActionArgumentSensitivity.REDACTED}" in warnings)
        assertTrue(warnings.toString(), warnings.none { "abc123" in it })
    }

    // A plain `}}` held in another variable, for text that closes a block only once it's filled in.
    private val plainGlobals = mapOf("Close" to "}}")

    private fun runLog(vararg steps: ActionSpec): String {
        val store = VariableStore().apply {
            set("Token", "abc123", sensitive = true)
            plainGlobals.forEach { (name, value) -> set(name, value) }
        }
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, logger = {})).run(Task(name = "T", actions = steps.toList()))
        }
        return runLogMessage(source = "manual", traces = report.traces) + report.traces
    }

    private fun preflightAllWarnings(vararg steps: ActionSpec) = PreflightRunner.preflightTask(
        Task(id = 1, name = "T", actions = steps.toList()),
        inputs = PreflightInputs(globalVariables = mapOf("Token" to "abc123") + plainGlobals, secretGlobalNames = setOf("Token")),
    ).tasks.single().steps.flatMap { it.warnings }

    @Test
    fun aSecretReadThroughAHyphenatedNameStaysHidden() {
        // `Token-x` isn't set, so `%Token-x` reads `%Token` followed by `-x`.
        assertFalse(runLog(setColor("{{ %Token-x }}")).contains("abc123"))
        assertTrue(preflightAllWarnings(setColor("{{ %Token-x }}")).none { "abc123" in it })
    }

    @Test
    fun aBlockClosedByAnotherVariableStillHidesTheSecret() {
        assertFalse(runLog(setColor("{{ %Token%Close")).contains("abc123"))
        assertTrue(preflightAllWarnings(setColor("{{ %Token%Close")).none { "abc123" in it })
    }

    @Test
    fun preflightKeepsACopyOfASecretSecret() {
        val copy = ActionSpec(type = "var.set", args = mapOf("name" to "%copy", "value" to "%Token"))
        val warnings = preflightAllWarnings(copy, setColor("{{ %copy }}"))
        assertTrue(warnings.toString(), warnings.none { "abc123" in it })
    }

    @Test
    fun preflightKeepsACopyMadeByMultipleVariablesSetSecret() {
        val copy = ActionSpec(type = "var.set_multiple", args = mapOf("names" to "%copy", "values" to "%Token"))
        val warnings = preflightAllWarnings(copy, setColor("{{ %copy }}"))
        assertTrue(warnings.toString(), warnings.none { "abc123" in it })
    }

    /**
     * The stored run log and the traces of steps run on [store], with the app log lines in [logs].
     * A trace keeps a secret change's value in memory, marked sensitive, for the run log to hide.
     */
    private fun runOn(store: VariableStore, logs: MutableList<String>, vararg steps: ActionSpec): Pair<String, List<ActionExecutionTrace>> {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, logger = { logs += it })).run(Task(name = "T", actions = steps.toList()))
        }
        return runLogMessage(source = "manual", traces = report.traces) to report.traces
    }

    @Test
    fun aChangedReadOfASecretInABlockClosedByAnotherVariableStaysHidden() {
        assertFalse(runLog(setColor("{{ %Token(upper)%Close")).contains("ABC123"))
        assertTrue(preflightAllWarnings(setColor("{{ %Token(upper)%Close")).none { "ABC123" in it })
    }

    @Test
    fun anItemOfASecretArrayInABlockClosedByAnotherVariableStaysHidden() {
        val store = VariableStore().apply {
            setArray("Secrets", listOf("abc123", "abc123"), sensitive = true)
            plainGlobals.forEach { (name, value) -> set(name, value) }
        }
        val (log, traces) = runOn(store, mutableListOf(), setColor("{{ %Secrets(1)%Close"))
        assertFalse(log, log.contains("abc123"))
        assertFalse(traces.toString(), traces.single().templateWarnings.any { "abc123" in it })
    }

    @Test
    fun anItemReadFromASecretArrayWithAHyphenInItsNameStaysSecret() {
        // %my-arr(1) reads the array my-arr, never %my followed by -arr(1), even with %my set.
        val store = VariableStore().apply {
            setArray("my-arr", listOf("abc123", "abc123"), sensitive = true)
            set("my", "plain")
        }
        val logs = mutableListOf<String>()
        val (log, traces) = runOn(store, logs, ActionSpec(type = "var.set", args = mapOf("name" to "%out", "value" to "%my-arr(1)")))
        assertTrue(traces.single().variableChanges.single { it.name == "out" }.sensitive)
        assertFalse(log, log.contains("abc123"))
        assertFalse(logs.toString(), logs.any { "abc123" in it })
    }
}
