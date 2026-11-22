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
 * Replace Text's Edit in place, which Tasker's Search Replace imports as. Tasker 6.7.8's handler
 * (`ExecuteService.V2`) fails on an unset variable before it empties the array, writes the variable
 * back only when something matched, unsets it when the result is empty, and logs a replacement it
 * can't make and goes on. Globals are used so what the step leaves can be read after the run.
 */
class TextReplaceInPlaceTest {
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun searchReplace(
        pattern: String,
        with: String?,
        vararg extra: Pair<String, String>,
        name: String = "Text",
        inPlace: Boolean = true,
    ) = ActionSpec(
        type = "text.replace",
        args = buildMap {
            put("source", "{{ $name }}")
            put("pattern", pattern)
            if (with != null) put("replacement", with) else put("search_only", "true")
            put("var", "%$name")
            if (inPlace) put("in_place", "true")
            putAll(extra)
        },
    )

    private fun run(store: VariableStore, step: ActionSpec): Boolean = run(store, listOf(step))

    private fun run(store: VariableStore, steps: List<ActionSpec>): Boolean = runBlocking {
        logs.clear()
        TaskRunner(ActionContext(ContextWrapper(null), store, logger = { logs += it }))
            .run(Task(id = 1, name = "Search Replace", actions = steps)).success
    }

    private fun storedGlobals(store: VariableStore) = store.toTemplateScope().global

    @Test
    fun anUnsetVariableFailsTheStepAndLeavesTheArrayAlone() {
        val store = VariableStore().apply { set("Found1", "old") }
        assertFalse(run(store, searchReplace("x", "y", "matches" to "Found")))
        assertEquals("old", store.get("Found1"))
        assertNull(store.get("Text"))
    }

    @Test
    fun nothingMatchedWritesNothingBack() {
        // A built-in written back would be stored and stop following the clock.
        val store = VariableStore()
        assertTrue(run(store, searchReplace("x", "y", name = "TIME")))
        assertFalse(storedGlobals(store).toString(), "TIME" in storedGlobals(store))
    }

    @Test
    fun aResultWithNothingLeftUnsetsTheVariable() {
        val store = VariableStore().apply { set("Text", "aaa") }
        assertTrue(run(store, searchReplace("a+", "")))
        assertFalse(storedGlobals(store).toString(), "Text" in storedGlobals(store))
    }

    @Test
    fun aResultWithNothingLeftUnsetsEveryCopyInTheTask() {
        // An event's variables are stored before the run opens its own scope, so the first step's
        // write sits over the stored value. Unsetting only that write brought "abc" back.
        val store = VariableStore().apply { set("text", "abc") }
        assertTrue(
            run(
                store,
                listOf(
                    searchReplace("b", "", name = "text"),
                    searchReplace(".+", "", name = "text"),
                    ActionSpec(type = "var.set", args = mapOf("name" to "%Out", "value" to "[%text]")),
                ),
            ),
        )
        // Unset reads as written or as nothing, depending on the step; never as the old value.
        assertTrue(store.get("Out").toString(), store.get("Out") in setOf("[%text]", "[]"))
    }

    @Test
    fun aMissingGroupLeavesTheVariableAsItWasAndTheStepGoesOn() {
        val store = VariableStore().apply { set("Text", "abc") }
        assertTrue(run(store, searchReplace("b", "[$9]", "matches" to "Found")))
        assertEquals("abc", store.get("Text"))
        assertEquals("b", store.get("Found1"))
        assertTrue(logs.toString(), logs.any { "couldn't replace" in it })
    }

    @Test
    fun aMatchIsReplacedAsBefore() {
        val store = VariableStore().apply { set("Text", "a<b<c") }
        assertTrue(run(store, searchReplace("<", "~lt~")))
        assertEquals("a~lt~b~lt~c", store.get("Text"))
        assertTrue(run(store, searchReplace("~lt~", "<", "first_only" to "true")))
        assertEquals("a<b~lt~c", store.get("Text"))
    }

    @Test
    fun aBadPatternStillFailsAndEmptiesTheArray() {
        val store = VariableStore().apply {
            set("Text", "abc")
            set("Found1", "old")
        }
        assertFalse(run(store, searchReplace("(", "y", "matches" to "Found")))
        assertNull(store.get("Found1"))
        assertEquals("abc", store.get("Text"))
    }

    @Test
    fun withoutInPlaceAnUnsetSourceStillWritesTheResult() {
        val store = VariableStore()
        assertTrue(run(store, searchReplace("x", "y", inPlace = false)))
        assertEquals("", storedGlobals(store)["Text"])
    }
}
