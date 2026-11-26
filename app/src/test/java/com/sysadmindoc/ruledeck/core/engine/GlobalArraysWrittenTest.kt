package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A run tells the commit which global arrays it wrote as a whole, so their elements save together
 * (`VariableRepository.persistRuntimeAtomically`), while a single element set by name stays a name.
 */
class GlobalArraysWrittenTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    @Test
    fun anArrayActionOnAGlobalIsRecordedAndASingleSetIsnt() {
        val store = VariableStore().apply { set("Arr1", "a") }
        val steps = listOf(
            ActionSpec(type = "array.push", args = mapOf("array" to "%Arr", "position" to "2", "value" to "b")),
            ActionSpec(type = "var.set", args = mapOf("name" to "%Other3", "value" to "x")),
        )
        val report = runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Push", actions = steps)) }

        assertTrue(report.results.toString(), report.success)
        assertEquals("b", store.get("Arr2"))
        assertEquals(setOf("Arr"), store.globalArraysWritten())
    }
}
