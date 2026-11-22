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

/**
 * Array Push, Pop and Process on a list Split Text kept: they read it as elements 1..n and leave
 * numbered elements that Join Text, For Each and `%arr(#)` all read. They used to change only
 * numbered elements, so Join Text and For Each went on reading the untouched list. Globals keep the
 * result past the run.
 */
class ArrayActionsOnAKeptListTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun splitText(source: String) =
        ActionSpec(type = "text.split", args = mapOf("source" to source, "delimiter" to ",", "var" to "Parts"))

    private fun step(type: String, vararg args: Pair<String, String>) = ActionSpec(type = type, args = mapOf("array" to "%Parts") + args)

    private fun run(store: VariableStore, vararg actions: ActionSpec) {
        val report = report(store, *actions)
        assertTrue(report.results.toString(), report.success)
    }

    private fun report(store: VariableStore, vararg actions: ActionSpec, tasks: Map<String, Task> = emptyMap()) = runBlocking {
        TaskRunner(ActionContext(ContextWrapper(null), store), resolveTask = { ref -> tasks[ref] })
            .run(Task(id = 1, name = "Arrays", actions = actions.toList()))
    }

    /** What Join Text and For Each read, then what `%Parts(#)` reads. */
    private fun read(store: VariableStore) = store.getArrayItems("Parts").orEmpty().joinToString(",") to store.expand("%Parts(#)")

    @Test
    fun pushAddsToTheKeptList() {
        val store = VariableStore()
        run(store, splitText("a,b,c"), step("array.push", "position" to "1", "value" to "z"))
        assertEquals("z,a,b,c" to "4", read(store))
    }

    @Test
    fun popTakesFromTheKeptList() {
        val store = VariableStore()
        run(store, splitText("a,b,c"), step("array.pop", "position" to "1", "to_var" to "%Got"))
        assertEquals("b,c" to "2", read(store))
        assertEquals("a", store.get("Got"))
    }

    @Test
    fun processReordersTheKeptList() {
        val store = VariableStore()
        run(store, splitText("a,b,c"), step("array.process", "type" to "reverse"))
        assertEquals("c,b,a" to "3", read(store))
    }

    @Test
    fun aSecretKeptListStaysSecretAsElements() {
        val store = VariableStore().apply { set("Token", "x1,x2", sensitive = true) }
        run(store, ActionSpec(type = "text.split", args = mapOf("source" to "%Token", "delimiter" to ",", "var" to "Parts")), step("array.process", "type" to "reverse"))
        assertEquals("x2,x1" to "2", read(store))
        assertTrue(store.isSensitive("Parts1"))
        assertTrue(store.isSensitive("Parts2"))
    }

    @Test
    fun numberedElementsStillWinOverAKeptList() {
        val store = VariableStore().apply {
            set("Parts1", "n1")
            set("Parts2", "n2")
        }
        run(store, splitText("a,b,c"), step("array.push", "value" to "z"))
        // The numbered elements already there are what Push moves, and the list Split Text kept no
        // longer hides them from Join Text and For Each.
        assertEquals("z,n1,n2" to "3", read(store))
    }

    @Test
    fun aListWhoseElementNamesWouldBeTooLongFailsAndStaysAsItWas() {
        // Names stop at 64 characters, so a 63-character array can have elements 1 to 9 but not 10.
        val name = "L" + "x".repeat(62)
        val store = VariableStore()
        val split = ActionSpec(type = "text.split", args = mapOf("source" to "a,b,c,d,e,f,g,h,i,j", "delimiter" to ",", "var" to name))
        val reverse = ActionSpec(type = "array.process", args = mapOf("array" to "%$name", "type" to "reverse"))
        assertFalse(report(store, split, reverse).success)
        assertEquals("a,b,c,d,e,f,g,h,i,j", store.getArrayItems(name).orEmpty().joinToString(","))
        // One popped leaves nine, and their names fit.
        val pop = ActionSpec(type = "array.pop", args = mapOf("array" to "%$name", "position" to "1"))
        assertTrue(report(store, pop).success)
        assertEquals("b,c,d,e,f,g,h,i,j", store.getArrayItems(name).orEmpty().joinToString(","))
    }

    @Test
    fun aListOverTheElementCapFailsAndStaysAsItWas() {
        val store = VariableStore()
        assertFalse(report(store, splitText(List(10_001) { "x$it" }.joinToString(",")), step("array.process", "type" to "reverse")).success)
        assertEquals(10_001, store.getArrayItems("Parts")?.size)
        assertEquals("x0", store.getArrayItems("Parts")?.first())
    }

    @Test
    fun aSubTaskChangingItsCallersListLeavesTheResultWithTheCaller() {
        val inner = Task(id = 2, name = "Inner", actions = listOf(ActionSpec(type = "array.pop", args = mapOf("array" to "%parts", "position" to "1"))))
        val store = VariableStore()
        val outcome = report(
            store,
            ActionSpec(type = "text.split", args = mapOf("source" to "a,b,c", "delimiter" to ",", "var" to "parts")),
            ActionSpec(type = "task.run", args = mapOf("task" to "Inner")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Out", "value" to "%parts(#):%parts(+;)")),
            tasks = mapOf("Inner" to inner),
        )
        assertTrue(outcome.results.toString(), outcome.success)
        assertEquals("2:b;c", store.get("Out"))
    }

    @Test
    fun aSubTaskChangingAListWithNoBaseLeavesTheResultWithTheCaller() {
        // Wi-Fi Scan keeps %wifi_ssid with no %wifi_ssid of its own.
        val inner = Task(id = 2, name = "Inner", actions = listOf(ActionSpec(type = "array.pop", args = mapOf("array" to "%wifi_ssid", "position" to "1"))))
        val store = VariableStore().apply { setArray("wifi_ssid", listOf("a", "b", "c")) }
        val outcome = report(
            store,
            ActionSpec(type = "task.run", args = mapOf("task" to "Inner")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Out", "value" to "%wifi_ssid(#):%wifi_ssid(+;)")),
            tasks = mapOf("Inner" to inner),
        )
        assertTrue(outcome.results.toString(), outcome.success)
        assertEquals("2:b;c", store.get("Out"))
    }
}
