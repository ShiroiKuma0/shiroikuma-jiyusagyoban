package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Variable Clear of a path (`%json.key`, `%json.list[0]`) removes that field or item from the JSON
 * the variable holds, the way Tasker 6.7.8 clears `%json.key` (`TaskerJsonWriter.d`): a removed
 * list item moves the later ones down, and a field or item that isn't there, an unset variable or
 * one that isn't JSON fails the step and changes nothing.
 */
class VariableClearPathTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private val json = """{"a":1,"b":{"c":2,"d":null},"list":[10,20,30]}"""

    private fun clear(path: String) = ActionSpec(type = "var.clear", args = mapOf("name" to path))

    private fun run(store: VariableStore, path: String) = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Clear", actions = listOf(clear(path))))
    }

    private fun failure(report: TaskRunReport) = (report.results.single() as ActionResult.Failure).message

    @Test
    fun aFieldGoesAndTheRestStays() {
        val store = VariableStore().apply { set("Json", json) }
        assertTrue(run(store, "%Json.b.c").success)
        assertEquals("""{"a":1,"b":{"d":null},"list":[10,20,30]}""", store.get("Json"))
        // A field that holds null is there to remove.
        assertTrue(run(store, "%Json.b.d").success)
        assertEquals("""{"a":1,"b":{},"list":[10,20,30]}""", store.get("Json"))
    }

    @Test
    fun aListItemGoesAndTheLaterOnesMoveDown() {
        val store = VariableStore().apply { set("Json", json) }
        assertTrue(run(store, "%Json.list[1]").success)
        assertEquals("""{"a":1,"b":{"c":2,"d":null},"list":[10,30]}""", store.get("Json"))
        val list = VariableStore().apply { set("Items", """[{"n":"x"},{"n":"y"}]""") }
        assertTrue(run(list, "%Items[0].n").success)
        assertEquals("""[{},{"n":"y"}]""", list.get("Items"))
    }

    @Test
    fun whatIsntThereFailsTheStepAndChangesNothing() {
        val store = VariableStore().apply { set("Json", json) }
        mapOf(
            "%Json.zz" to "field 'zz' doesn't exist",
            "%Json.list[3]" to "item [3] is out of range (the list has 3 items)",
            "%Json.a.x" to "'a' is a value, not a list or object",
            "%Json.b.d.x" to "field 'd' doesn't exist",
            "%Json.list.x" to "can't remove field 'x' from a list",
            "%Json[0]" to "can't remove item [0] from an object",
        ).forEach { (path, why) ->
            val report = run(store, path)
            assertFalse(path, report.success)
            assertEquals("$path: $why", failure(report))
            assertEquals(path, json, store.get("Json"))
        }
    }

    @Test
    fun anUnsetVariableOrOneThatIsntJsonFailsTheStep() {
        val store = VariableStore().apply { set("Text", "hello") }
        assertEquals("%Nope.a: %Nope has no value to change", failure(run(store, "%Nope.a")))
        assertEquals("%Text.a: %Text isn't valid JSON", failure(run(store, "%Text.a")))
        assertEquals("hello", store.get("Text"))
    }

    @Test
    fun aSecretVariableStaysSecret() {
        // A local, since a global's secret flag never drops whatever writes it.
        val store = VariableStore().apply {
            pushScope()
            set("json", json, sensitive = true)
        }
        assertNull(store.clearAtPath("%json.a"))
        assertEquals("""{"b":{"c":2,"d":null},"list":[10,20,30]}""", store.get("json"))
        assertTrue(store.isSensitive("json"))
    }

    @Test
    fun brokenJsonNestedDeepFailsTheStepWithoutParsingIt() {
        // kotlinx reads down an unclosed `[[[` before it refuses it, deep enough to overflow the stack.
        val deep = "[".repeat(100_000)
        val store = VariableStore().apply { set("Deep", deep) }
        assertEquals("%Deep[0]: %Deep isn't valid JSON", failure(run(store, "%Deep[0]")))
        assertEquals(deep, store.get("Deep"))
    }
}
