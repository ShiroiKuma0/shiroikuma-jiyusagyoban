package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.LocationGetAction
import com.sysadmindoc.ruledeck.core.actions.LocationReading
import com.sysadmindoc.ruledeck.core.actions.LocationReadings
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A Get Location step as the run log keeps it: all four variables listed, the coordinates hidden. */
class LocationGetRunLogTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
        ActionRegistry.register(
            LocationGetAction(
                permitted = { true },
                readings = {
                    object : LocationReadings {
                        override fun enabledProviders() = listOf("gps", "network", "passive")
                        override fun lastKnown(provider: String): LocationReading? = null
                        override fun start(providers: List<String>, onFix: (LocationReading) -> Unit): () -> Unit {
                            onFix(LocationReading(40.6972846, -74.1443129, 8.0, 1_000L, "gps"))
                            return {}
                        }
                    }
                },
            ),
        )
    }

    @After
    fun tearDown() {
        registerCoreRuntime()
    }

    @Test
    fun theRunLogListsTheFourVariablesWithTheCoordinatesHidden() {
        val logs = mutableListOf<String>()
        val report = runBlocking {
            TaskRunner(ActionContext(ContextWrapper(null), VariableStore(), logger = { logs += it }))
                .run(Task(name = "Where am I", actions = listOf(ActionSpec(type = "location.get"))))
        }
        assertTrue(report.success)

        val serialized = runLogMessage(source = "manual", traces = report.traces)
        val changes = serialized.toRunLogDiagnostics().traces.flatMap { it.variableChanges }.associate { it.name to it.value }

        assertEquals(
            mapOf(
                "location_lat" to "<redacted>",
                "location_lon" to "<redacted>",
                "location_accuracy" to "8",
                "location_age" to "1",
            ),
            changes,
        )
        assertFalse(serialized, "40.69" in serialized || "74.14" in serialized)
        assertFalse(logs.toString(), logs.any { "40.69" in it || "74.14" in it })
    }
}
