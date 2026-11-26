package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Set Variable on a path written with `%`, the way a name is usually written, writes like the bare path. */
class SetVariablePercentPathTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
        registerActionMetadata()
    }

    private fun setStep(name: String, value: String) = ActionSpec(type = "var.set", args = mapOf("name" to name, "value" to value))

    /** Runs [steps] and returns whether every step succeeded and the last value each changed variable took. */
    private fun run(vararg steps: ActionSpec, globals: Map<String, String> = emptyMap()): Pair<Boolean, Map<String, String>> {
        val store = VariableStore().apply { globals.forEach { (name, value) -> set(name, value) } }
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), store, logger = {})).run(Task(name = "T", actions = steps.toList()))
        }
        val changes = report.traces.flatMap { it.variableChanges }.associate { it.name to it.value }
        return report.success to changes
    }

    private fun assertWritesLikeTheBarePath(percent: String, value: String, globals: Map<String, String> = emptyMap(), vararg before: ActionSpec) {
        val bare = run(*before, setStep(percent.removePrefix("%"), value), globals = globals)
        val withPercent = run(*before, setStep(percent, value), globals = globals)
        assertTrue("$percent bare: $bare", bare.first)
        assertEquals(percent, bare, withPercent)
    }

    @Test
    fun aPathWithAPercentSignWritesWhenItsVariableIsUnset() {
        assertWritesLikeTheBarePath("%config.theme", "dark")
        assertEquals("""{"theme":"dark"}""", run(setStep("%config.theme", "dark")).second["config"])
    }

    @Test
    fun aPathWithAPercentSignWritesIntoAVariableThatHoldsJson() {
        val withJson = mapOf("Config" to """{"theme":"light","size":2}""")
        assertWritesLikeTheBarePath("%Config.theme", "dark", withJson)
        assertEquals("""{"theme":"dark","size":2}""", run(setStep("%Config.theme", "dark"), globals = withJson).second["Config"])
        // A local holding JSON, set by an earlier step, the case that read `{...}.theme` before.
        assertWritesLikeTheBarePath("%config.theme", "dark", emptyMap(), setStep("%config", """{"theme":"light"}"""))
    }

    @Test
    fun nestedGlobalAndIndexPathsWriteLikeTheirBareForms() {
        assertWritesLikeTheBarePath("%Config.user.name", "Ada")
        assertEquals("""{"user":{"name":"Ada"}}""", run(setStep("%Config.user.name", "Ada")).second["Config"])
        assertWritesLikeTheBarePath("%items[0]", "a")
        assertEquals("""["a"]""", run(setStep("%items[0]", "a")).second["items"])
    }

    @Test
    fun aKeyReadFromAVariableIsStillFilledIn() {
        val (success, changes) = run(setStep("%key", "theme"), setStep("%config.%key", "dark"))
        assertTrue(success)
        assertEquals("""{"theme":"dark"}""", changes["config"])
        // The same with the base already holding JSON, so neither part of the name reads it.
        val (_, withJson) = run(setStep("%key", "theme"), setStep("%config", """{"size":2}"""), setStep("%config.%key", "dark"))
        assertEquals("""{"size":2,"theme":"dark"}""", withJson["config"])
    }

    private fun preflightShows(vararg steps: ActionSpec, globals: Map<String, String> = emptyMap()): String? =
        PreflightRunner.preflightTask(
            Task(id = 1, name = "T", actions = steps.toList() + setStep("%Out", "%Config")),
            inputs = PreflightInputs(globalVariables = globals),
        ).tasks.single().steps.last().expandedArguments["value"]

    @Test
    fun spacesAroundAPathAreTrimmedInTheRunAsInPreflight() {
        assertWritesLikeTheBarePath("%config.theme", "dark")
        assertEquals(run(setStep("%config.theme", "dark")), run(setStep(" %config.theme ", "dark")))
        assertEquals(run(setStep("config.theme", "dark")), run(setStep("config.theme ", "dark")))
        assertEquals("""{"theme":"dark"}""", preflightShows(setStep(" %Config.theme ", "dark")))
    }

    @Test
    fun preflightKeepsAPathWrittenFromASecretSecret() {
        val shown = PreflightRunner.preflightTask(
            Task(id = 1, name = "T", actions = listOf(setStep("%Config.key", "%Token"), setStep("%Out", "%Config"))),
            inputs = PreflightInputs(globalVariables = mapOf("Token" to "abc123"), secretGlobalNames = setOf("Token")),
        ).tasks.single().steps.last()
        assertTrue(shown.toString(), "abc123" !in shown.toString())
    }

    @Test
    fun preflightWritesAPathWithAPercentSignLikeTheRun() {
        val withJson = mapOf("Config" to """{"theme":"light"}""")
        assertEquals("""{"theme":"dark"}""", preflightShows(setStep("%Config.theme", "dark"), globals = withJson))
        assertEquals("""{"theme":"dark"}""", preflightShows(setStep("Config.theme", "dark"), globals = withJson))
        assertEquals("""{"theme":"dark"}""", preflightShows(setStep("%Config.theme", "dark")))
        assertEquals("""{"theme":"dark"}""", preflightShows(setStep("%key", "theme"), setStep("%Config.%key", "dark")))
    }
}
