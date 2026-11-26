package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.engine.variables.BuiltInVariables
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The built-in variables (`%TIME`, `%DATE`, `%BATT`, ...) fill in whenever a task reads them. A task
 * isn't meant to set one, so a value stored under a built-in's name is a conflict Preflight should
 * name.
 */
class BuiltInVariablesTest {
    private val recorded = mutableListOf<String>()

    // Friday, July 4, 2025, 9:05:07.123 AM in New York (EDT, four hours behind UTC).
    private val now = Instant.parse("2025-07-04T13:05:07.123Z")

    @Before
    fun setUp() {
        registerCoreRuntime()
        ActionRegistry.register(object : Action {
            override val id = "test.builtin.record"
            override val category = ActionCategory.FLOW
            override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
                recorded += args.getValue("v")
                return ActionResult.Success
            }
        })
        BuiltInVariables.clockForTests = Clock.fixed(now, ZoneId.of("America/New_York"))
        BuiltInVariables.localeForTests = Locale.US
        BuiltInVariables.installDevice { name -> mapOf("BATT" to "15", "SCREEN" to "on")[name] }
    }

    @After
    fun tearDown() {
        BuiltInVariables.resetForTests()
        registerCoreRuntime()
    }

    private fun record(text: String, condition: String? = null) =
        ActionSpec(type = "test.builtin.record", args = mapOf("v" to text), condition = condition)

    private fun run(vararg actions: ActionSpec, store: VariableStore = VariableStore()): List<String> = runBlocking {
        recorded.clear()
        val report = TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(name = "Built-ins", actions = actions.toList()))
        assertTrue(report.results.toString(), report.success)
        recorded.toList()
    }

    @Test
    fun theClockBuiltInsFillIn() {
        assertEquals(
            listOf("09.05|${now.epochSecond}|${now.toEpochMilli()}|7-4-25|Friday|4"),
            run(record("%TIME|%TIMES|%TIMEMS|%DATE|%DAYW|%DAYM")),
        )
    }

    @Test
    fun theDeviceBuiltInsComeFromTheDeviceReader() {
        // The reader here has no %SDK, so that one stays as written.
        assertEquals(listOf("battery 15, screen on, sdk %SDK"), run(record("battery %BATT, screen %SCREEN, sdk %SDK")))
    }

    @Test
    fun aNameThatOnlyStartsWithABuiltInIsntOne() {
        assertEquals(listOf("%TIMEOUT %BATTERY 09.05-ish"), run(record("%TIMEOUT %BATTERY %TIME-ish")))
    }

    @Test
    fun conditionsReadTheBuiltIns() {
        assertEquals(
            listOf("low", "has a time"),
            run(
                record("low", condition = "%BATT < 20"),
                record("high", condition = "%BATT > 20"),
                record("has a time", condition = "%TIME is_set"),
            ),
        )
    }

    @Test
    fun aVariableSplitOfTimeSplitsTheClock() {
        // Splitting the clock on its dot gives the hour and the minute.
        val split = ActionSpec(type = "var.split", args = mapOf("name" to "%TIME", "splitter" to "."))
        assertEquals(listOf("09 h 05"), run(split, record("%TIME1 h %TIME2")))

        val preview = PreflightRunner.preflightTask(Task(name = "Split", actions = listOf(split))).tasks.single().steps
        assertTrue(preview.single().warnings.toString(), preview.single().warnings.none { "built-in" in it })
    }

    @Test
    fun thePreviewFillsInTheBuiltInsToo() {
        val steps = PreflightRunner.preflightTask(
            Task(name = "Built-ins", actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "%TIME on %DAYW, battery %BATT")))),
        ).tasks.single().steps

        assertEquals("09.05 on Friday, battery 15", steps.single().expandedArguments["message"])
    }

    @Test
    fun aValueSetUnderABuiltInsNameWinsAndThePreviewSaysSo() {
        val actions = listOf(
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "lunch")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Time", "value" to "noon")),
            record("%TIME / %Time"),
        )
        assertEquals(listOf("lunch / noon"), run(*actions.toTypedArray()))

        val steps = PreflightRunner.preflightTask(Task(name = "Lunch", actions = actions)).tasks.single().steps
        assertTrue(steps[0].warnings.toString(), steps[0].warnings.any { "%TIME" in it && "built-in" in it })
        // Names are case-sensitive, so %Time is an ordinary global.
        assertTrue(steps[1].warnings.toString(), steps[1].warnings.none { "built-in" in it })
        assertEquals("lunch / noon", steps[2].expandedArguments["v"])
    }

    @Test
    fun thePreviewFillsInEveryClockBuiltIn() {
        val steps = PreflightRunner.preflightTask(
            Task(name = "Clock", actions = listOf(record("%TIME|%TIMES|%TIMEMS|%DATE|%DAYW|%DAYM"))),
        ).tasks.single().steps

        assertEquals("09.05|${now.epochSecond}|${now.toEpochMilli()}|7-4-25|Friday|4", steps.single().expandedArguments["v"])
    }

    @Test
    fun aSumTemplateReadsTheClock() {
        // "An hour from now": %Later is set to the clock's seconds plus 3600.
        val later = ActionSpec(type = "var.set", args = mapOf("name" to "%Later", "value" to "{{ TIMES | default:0 | add:3600 }}"))
        val actions = listOf(later) + record("%Later") + record("{{ global.TIMES }}")
        val expected = listOf("${now.epochSecond + 3600}", "${now.epochSecond}")

        assertEquals(expected, run(*actions.toTypedArray()))
        val steps = PreflightRunner.preflightTask(Task(name = "Later", actions = actions)).tasks.single().steps
        assertEquals(expected, steps.drop(1).map { it.expandedArguments["v"] })
    }

    @Test
    fun aBuiltInSetToNothingFillsInAgain() {
        val actions = listOf(
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "lunch")),
            record("%TIME"),
            // A Variable Set to nothing.
            ActionSpec(type = "var.set", args = mapOf("name" to "%TIME", "value" to "")),
            record("%TIME"),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Hour", "value" to "{{ TIME }}")),
            record("%Hour"),
        )

        assertEquals(listOf("lunch", "09.05", "09.05"), run(*actions.toTypedArray()))
    }

    @Test
    fun aStoredGlobalUnderABuiltInsNameStillWins() {
        val store = VariableStore().apply { seedGlobals(mapOf("TIME" to "teatime")) }

        assertEquals(listOf("teatime at teatime"), run(record("%TIME at {{ TIME }}"), store = store))
    }

    @Test
    fun thePreviewWarnsAboutWritesThatHideAFilledInBuiltInEvenOnASkippedStep() {
        val steps = PreflightRunner.preflightTask(
            Task(
                name = "Writes",
                actions = listOf(
                    // The battery reads 15, so this step is skipped here, but it runs on a full charge.
                    ActionSpec(type = "var.set", args = mapOf("name" to "%DATE", "value" to "today"), condition = "%BATT > 50"),
                    // %HTTPD and %HTTPR aren't built-ins here, so writing them is an ordinary step.
                    ActionSpec(
                        type = "http.request",
                        args = mapOf("method" to "GET", "url" to "https://example.invalid/", "response_var" to "%HTTPD", "status_var" to "%HTTPR"),
                    ),
                ),
            ),
        ).tasks.single().steps

        assertEquals(PreflightStepStatus.SKIPPED, steps[0].status)
        assertTrue(steps[0].warnings.toString(), steps[0].warnings.any { "%DATE" in it && "built-in" in it })
        assertTrue(steps[1].warnings.toString(), steps[1].warnings.none { "built-in" in it })
    }
}
