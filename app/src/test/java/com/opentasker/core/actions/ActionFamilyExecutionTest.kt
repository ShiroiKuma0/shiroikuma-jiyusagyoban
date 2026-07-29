package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Success and failure paths for a representative action from each family that runs without device
 * services. The families that need real Android state (settings, media, network, file sandbox) are
 * covered by ActionGuardsTest, PermissionDenialTest, HttpRequestActionTest, DownloadActionTest, and
 * FileActionsSandboxTest; this fills the gap for the transform families, which had no execution
 * coverage at all.
 */
class ActionFamilyExecutionTest {

    private lateinit var variables: VariableStore

    @Before
    fun setUp() {
        registerCoreRuntime()
        variables = VariableStore()
    }

    private fun run(actionId: String, args: Map<String, String>): ActionResult = runBlocking {
        val action = requireNotNull(ActionRegistry.get(actionId)) { "$actionId is not registered" }
        action.run(ActionContext(ContextWrapper(null), variables), args)
    }

    private fun assertFailed(result: ActionResult, expectedMessageFragment: String) {
        assertTrue("expected a failure but got $result", result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue("failure message '$message' should mention '$expectedMessageFragment'", expectedMessageFragment in message)
    }

    @Test
    fun variableSetWritesAndAMissingNameFailsClosed() {
        assertEquals(ActionResult.Success, run("var.set", mapOf("name" to "greeting", "value" to "hello")))
        assertEquals("hello", variables.get("greeting"))

        assertFailed(run("var.set", mapOf("value" to "hello")), "name")
    }

    @Test
    fun textReplaceWritesItsOutputAndAPathologicalPatternFailsClosed() {
        assertEquals(
            ActionResult.Success,
            run("text.replace", mapOf("source" to "a-b-c", "pattern" to "-", "replacement" to ":", "var" to "out")),
        )
        assertEquals("a:b:c", variables.get("out"))

        // A syntactically invalid pattern must not throw out of the action.
        assertFailed(run("text.replace", mapOf("source" to "abc", "pattern" to "([", "var" to "out")), "pattern")
        assertFailed(run("text.replace", mapOf("source" to "abc", "var" to "out")), "pattern")
    }

    @Test
    fun textMatchExposesGroupsAndCounts() {
        assertEquals(
            ActionResult.Success,
            run("text.match", mapOf("source" to "v1.2.3", "pattern" to """v(\d+)\.(\d+)""", "var" to "ver")),
        )
        assertEquals("v1.2", variables.get("ver"))
        assertEquals("2", variables.get("ver_count"))
        assertEquals(listOf("v1.2", "1", "2"), variables.getArrayItems("ver"))
    }

    @Test
    fun dateTimeRoundTripsAndRejectsAnUnparseableInput() {
        assertEquals(
            ActionResult.Success,
            run(
                "datetime.parse",
                mapOf("text" to "2026-07-29 08:30:00", "format" to "yyyy-MM-dd HH:mm:ss", "zone" to "UTC", "var" to "t"),
            ),
        )
        val epoch = requireNotNull(variables.get("t")).toLong()

        assertEquals(
            ActionResult.Success,
            run(
                "datetime.format",
                mapOf("time" to epoch.toString(), "format" to "yyyy-MM-dd", "zone" to "UTC", "var" to "day"),
            ),
        )
        assertEquals("2026-07-29", variables.get("day"))

        assertFailed(
            run("datetime.parse", mapOf("text" to "not a date", "format" to "yyyy-MM-dd", "var" to "t")),
            "could not parse",
        )
        assertFailed(run("datetime.format", mapOf("time" to "not-a-number", "var" to "d")), "invalid time")
    }

    @Test
    fun dateTimeAddRejectsAnUnknownUnitInsteadOfSilentlyDoingNothing() {
        assertEquals(
            ActionResult.Success,
            run("datetime.add", mapOf("time" to "0", "amount" to "90", "unit" to "minutes", "var" to "later")),
        )
        assertEquals((90L * 60_000).toString(), variables.get("later"))

        assertFailed(
            run("datetime.add", mapOf("time" to "0", "amount" to "1", "unit" to "fortnights", "var" to "later")),
            "invalid unit",
        )
    }

    @Test
    fun dataReadExtractsAJsonPathAndReportsAMissingOne() {
        assertEquals(
            ActionResult.Success,
            run(
                "data.read",
                mapOf("source" to """{"items":[{"name":"first"},{"name":"second"}]}""", "path" to "items[1].name", "var" to "d"),
            ),
        )
        assertEquals("second", variables.get("d"))

        assertFailed(
            run("data.read", mapOf("source" to """{"items":[]}""", "path" to "items[4].name", "var" to "d")),
            "could not read",
        )
        assertFailed(run("data.read", mapOf("path" to "a", "var" to "d")), "source")
    }

    @Test
    fun textSplitAndJoinRoundTripThroughTheArrayNamespace() {
        assertEquals(
            ActionResult.Success,
            run("text.split", mapOf("source" to "a,b,c", "delimiter" to ",", "var" to "parts")),
        )
        assertEquals("3", variables.get("parts_count"))

        assertEquals(
            ActionResult.Success,
            run("text.join", mapOf("array" to "parts", "delimiter" to "|", "var" to "rejoined")),
        )
        assertEquals("a|b|c", variables.get("rejoined"))

        assertFailed(run("text.split", mapOf("source" to "a,b,c", "var" to "parts")), "delimiter")
    }
}
