package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Arrays", actions = actions.toList())) }
        assertTrue(report.results.toString(), report.success)
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
}
