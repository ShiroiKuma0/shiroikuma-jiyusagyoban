package com.opentasker.core.engine

import android.content.ContextWrapper
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

/** A value typed under a secret-looking name stays out of a run's changes and log, as it stays out of the editor. */
class SecretNamedVariableRunLogTest {
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    /** The run log as stored, and each change read back from it by name. */
    private fun run(variables: VariableStore, vararg actions: ActionSpec): Pair<String, Map<String, String>> {
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), variables, logger = { logs += it }))
                .run(Task(name = "T", actions = actions.toList()))
        }
        val serialized = runLogMessage(source = "manual", traces = report.traces)
        val changes = serialized.toRunLogDiagnostics().traces.flatMap { it.variableChanges }.associate { it.name to it.value }
        return serialized to changes
    }

    private fun setVariable(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    private fun setMultiple(vararg args: Pair<String, String>) = ActionSpec(type = "var.set_multiple", args = mapOf(*args))

    @Test
    fun aValueSetUnderASecretNameIsHidden() {
        val (serialized, changes) = run(VariableStore(), setVariable("%api_token", "hunter2"))
        assertEquals(mapOf("api_token" to "<redacted>"), changes)
        assertFalse(serialized, "hunter2" in serialized)
        assertFalse(logs.toString(), logs.any { "hunter2" in it })
    }

    @Test
    fun aSecretLookingPathHidesTheVariableItWritesInto() {
        val (serialized, changes) = run(VariableStore(), setVariable("config.api_key", "hunter2"))
        assertEquals(mapOf("config" to "<redacted>"), changes)
        assertFalse(serialized, "hunter2" in serialized)
        assertFalse(logs.toString(), logs.any { "hunter2" in it })
    }

    @Test
    fun onlyTheSecretNameAmongSeveralIsHidden() {
        val inline = run(VariableStore(), setMultiple("names" to "%user,sam\n%api_token,hunter2")).second
        val listed = run(VariableStore(), setMultiple("names" to "%user\n%api_token", "values" to "sam\nhunter2")).second
        assertEquals(mapOf("user" to "sam", "api_token" to "<redacted>"), inline)
        assertEquals(mapOf("user" to "sam", "api_token" to "<redacted>"), listed)
    }

    @Test
    fun anOrdinaryNameStillShowsItsValue() {
        val (_, changes) = run(VariableStore(), setVariable("%greeting", "hello"))
        assertEquals(mapOf("greeting" to "hello"), changes)
        assertTrue(logs.toString(), logs.any { "hello" in it })
    }

    @Test
    fun aNameBuiltFromASecretIsOnlyCountedAndItsValueHidden() {
        val variables = VariableStore()
        variables.set("Pin", "1234", sensitive = true)
        val (serialized, changes) = run(variables, setMultiple("names" to "%cache_%Pin,warm\n%b,1"))
        // The name itself holds the PIN, so the change list counts it as the app log line does.
        assertFalse(serialized, "cache_1234" in serialized)
        assertEquals("1", changes["b"])
        assertEquals(2, changes.size)
        assertEquals(listOf("var.set_multiple -> set 2 variable(s)"), logs)
    }

    @Test
    fun setVariableOnANameBuiltFromASecretKeepsTheNameOut() {
        val variables = VariableStore()
        variables.set("Pin", "1234", sensitive = true)
        val (serialized, _) = run(variables, setVariable("%cache_%Pin", "warm"))
        assertFalse(serialized, "cache_1234" in serialized)
        assertFalse(logs.toString(), logs.any { "cache_1234" in it })
    }
}
