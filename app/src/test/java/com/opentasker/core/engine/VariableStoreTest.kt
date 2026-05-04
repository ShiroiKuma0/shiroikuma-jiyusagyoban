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
}
