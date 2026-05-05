package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VariableStoreTest {
    @Test
    fun localScopeShadowsGlobalUntilPopped() {
        val store = VariableStore()
        store.set("value", "global")
        store.pushScope()
        store.set("value", "local")

        assertEquals("local", store.expand("%value"))

        store.popScope()

        assertEquals("global", store.expand("%value"))
    }

    @Test
    fun missingVariablesExpandToEmptyString() {
        val store = VariableStore()

        assertEquals("before  after", store.expand("before %missing after"))
    }

    @Test
    fun expandsEmbeddedOperatorsInText() {
        val store = VariableStore().apply {
            set("name", "open tasker")
            set("count", "7")
            set("score", "7.8")
        }

        assertEquals("OPEN TASKER-8-7", store.expand("%name(upper)-%count(+1)-%score(//)"))
        assertEquals("8", store.expand("%score(/round)"))
    }

    @Test
    fun expandsArrayTokens() {
        val store = VariableStore().apply {
            setArray("items", listOf("one", "two", "three"))
        }

        assertEquals("3", store.expand("%items(#)"))
        assertEquals("two", store.expand("%items(1)"))
        assertEquals("one,two,three", store.expand("%items(,)"))
    }
}
