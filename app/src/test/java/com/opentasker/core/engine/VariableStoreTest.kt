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

    @Test
    fun regexOperatorsDoNotCreateWorkerThreads() {
        val store = VariableStore().apply {
            set("text", "OpenTasker 123")
        }
        val threadCountBefore = regexEvalThreadCount()

        repeat(5) {
            assertEquals("123", store.expand("%text(regex:(\\d+):1)"))
            assertEquals("OpenTasker #", store.expand("%text(replace:\\d+:#)"))
        }

        assertEquals(threadCountBefore, regexEvalThreadCount())
    }

    @Test
    fun unsupportedRegexSyntaxFailsClosed() {
        val store = VariableStore().apply {
            set("text", "ab")
        }

        assertEquals("", store.expand("%text(regex:(?<=a)b:0)"))
        assertEquals("ab", store.expand("%text(replace:(?<=a)b:x)"))
    }

    private fun regexEvalThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "regex-eval" }
}
