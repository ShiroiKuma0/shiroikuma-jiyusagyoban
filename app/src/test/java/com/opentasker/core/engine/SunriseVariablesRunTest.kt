package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.SunTimesAction
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A sunrise task end to end: Get Sun Times on the phone's location, then Set Multiple Variables
 * copies the times into the globals a Time profile reads. The phone stands in New York before dawn
 * on 25 June 2025, the same moment SunTimeVariablesTest checks the numbers for.
 */
class SunriseVariablesRunTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(
            SunTimesAction(
                permitted = { true },
                locate = { 40.6972846 to -74.1443129 },
                nowMs = { 1750841218000L },
                zone = { TimeZone.getTimeZone("America/New_York") },
            ),
        )
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    private val task = Task(
        name = "Set Sunrise And Sunset Globals",
        actions = listOf(
            ActionSpec(type = "sun.times", args = emptyMap()),
            ActionSpec(
                type = "var.set_multiple",
                args = mapOf(
                    "names" to "%Sunrise\n%Sunset",
                    "values" to "%ss_sunrise\n%ss_sunset",
                    "names_splitter" to "\n",
                    "values_splitter" to "\n",
                ),
            ),
        ),
    )

    private fun runTask(store: VariableStore): Boolean {
        assertEquals(listOf("sun.times", "var.set_multiple"), task.actions.map { it.type })
        return runBlocking { TaskRunner(ActionContext(ContextWrapper(null), store)).run(task).success }
    }

    @Test
    fun theGlobalsTheTimeProfileReadsGetTheSunTimes() {
        val store = VariableStore()

        assertTrue(runTask(store))

        assertEquals("1750843560", store.get("Sunrise"))
        assertEquals("1750897860", store.get("Sunset"))
        // The %ss_ outputs are the task's own locals, so only the copies outlive the run.
        assertNull(store.get("ss_sunrise"))
    }

    @Test
    fun yesterdaysSunriseIsReplacedAndNeverReadAsAName() {
        val store = VariableStore()
        // A global, so a write through the old value would outlive the run and show up here.
        store.set("Sunrise", "%Leak")

        assertTrue(runTask(store))

        assertEquals("1750843560", store.get("Sunrise"))
        assertNull(store.get("Leak"))
    }
}
