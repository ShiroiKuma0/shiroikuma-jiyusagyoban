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
 * `(#)`, `(#<)` and `(#>)` count the numbered elements whatever the base itself holds, so they
 * read 0 when there are none. They used to fall back
 * to the base's own value, so after Split Text into %found and then an Array Clear, `%found(#)`
 * read the first part.
 */
class CountFormOnASetBaseTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    @Test
    fun aSetBaseWithNoElementsCountsNone() {
        val store = VariableStore().apply {
            set("found", "a")
            set("count", "7")
        }
        assertEquals("0", store.expand("%found(#)"))
        assertEquals("0", store.expand("%found(#<)"))
        assertEquals("0", store.expand("%found(#>)"))
        assertEquals("0", store.expand("%count(#)"))
    }

    @Test
    fun splitTextThenArrayClearCountsNone() {
        val store = VariableStore()
        val steps = listOf(
            ActionSpec(type = "text.split", args = mapOf("source" to "a,b", "delimiter" to ",", "var" to "Found")),
            ActionSpec(type = "array.clear", args = mapOf("array" to "%Found")),
        )
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Count", actions = steps)) }
        assertTrue(report.results.toString(), report.success)
        assertEquals("a", store.get("Found"))
        assertEquals("0", store.expand("%Found(#)"))
    }

    @Test
    fun aKeptListCountsFromOneAsTheArrayActionsDo() {
        val store = VariableStore().apply {
            set("parts", "a")
            setArray("parts", listOf("a", "b", "c"))
            setArray("none", emptyList())
        }
        assertEquals("3", store.expand("%parts(#)"))
        assertEquals("1", store.expand("%parts(#>)"))
        assertEquals("3", store.expand("%parts(#<)"))
        assertEquals("0", store.expand("%none(#>)"))
        assertEquals("0", store.expand("%none(#<)"))
    }

    @Test
    fun theCountsStillReadElementsAndMathsStillWorks() {
        val store = VariableStore().apply {
            set("found", "a")
            set("found2", "x")
            set("found5", "y")
            set("n", "10")
        }
        assertEquals("2", store.expand("%found(#)"))
        assertEquals("2", store.expand("%found(#>)"))
        assertEquals("5", store.expand("%found(#<)"))
        assertEquals("15", store.expand("%n(+5)"))
    }
}
