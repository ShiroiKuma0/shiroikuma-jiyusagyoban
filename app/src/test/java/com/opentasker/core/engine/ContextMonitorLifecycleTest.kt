package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMonitorLifecycleTest {
    @Test
    fun noDependentProfilesStartNoProductionMonitors() {
        val harness = MonitorHarness()
        val disabled = profile(1, ContextSpec(ContextType.APPLICATION)).copy(enabled = false)
        val awaitingReview = profile(2, ContextSpec(ContextType.EVENT, mapOf("event" to "shake")))
            .copy(requiresRiskAcknowledgement = true)

        val transition = harness.lifecycle.reconcile(listOf(disabled, awaitingReview))

        assertEquals(ContextMonitorTransition(), transition)
        assertEquals(emptyMap<ContextMonitor, Int>(), harness.lifecycle.currentReferenceCounts())
        assertEquals(emptySet<ContextMonitor>(), harness.lifecycle.currentlyStarted())
        assertEquals(emptyList<String>(), harness.events)
    }

    @Test
    fun sharedProfileDemandStartsAndStopsExactlyOnceAtReferenceCountBoundaries() {
        val harness = MonitorHarness()
        val first = profile(
            1,
            ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Office")),
            ContextSpec(ContextType.STATE, mapOf("key" to "wifi_connected", "value" to "true")),
        )
        val second = profile(2, ContextSpec(ContextType.STATE, mapOf("predicate" to "ssid=Office")))

        // Both profiles compare a name, so each also owns the name demand.
        harness.lifecycle.reconcile(listOf(first, second))
        assertEquals(2, harness.lifecycle.currentReferenceCounts()[ContextMonitor.WIFI])
        assertEquals(listOf("start:WIFI_NAME", "start:WIFI"), harness.events)

        harness.lifecycle.reconcile(listOf(second))
        assertEquals(1, harness.lifecycle.currentReferenceCounts()[ContextMonitor.WIFI])
        assertEquals(listOf("start:WIFI_NAME", "start:WIFI"), harness.events)

        harness.lifecycle.reconcile(emptyList())
        assertEquals(listOf("start:WIFI_NAME", "start:WIFI", "stop:WIFI", "stop:WIFI_NAME"), harness.events)
        assertEquals(emptySet<ContextMonitor>(), harness.lifecycle.currentlyStarted())
    }

    @Test
    fun addEditDisableDeleteReconcilesOnlyChangedMonitorBoundaries() {
        val harness = MonitorHarness()
        val app = profile(1, ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example")))
        val network = profile(2, ContextSpec(ContextType.STATE, mapOf("key" to "internet", "value" to "true")))

        harness.lifecycle.reconcile(listOf(app, network))
        harness.lifecycle.reconcile(
            listOf(
                app.copy(contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "shake")))),
                network.copy(enabled = false),
            ),
        )
        harness.lifecycle.reconcile(emptyList())

        assertEquals(
            listOf(
                "start:CONNECTIVITY",
                "start:APP_USAGE",
                "start:SHAKE",
                "stop:APP_USAGE",
                "stop:CONNECTIVITY",
                "stop:SHAKE",
            ),
            harness.events,
        )
    }

    @Test
    fun eventAndStateConfigsSelectOnlyTheirRequiredOsSources() {
        val profile = profile(
            1,
            ContextSpec(ContextType.STATE, mapOf("predicate" to "vpn = true")),
            ContextSpec(ContextType.STATE, mapOf("key" to "wifi_connected", "value" to "true")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "microphone")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "package_replaced")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "bluetooth")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "bluetooth_all_disconnected")),
            ContextSpec(ContextType.EVENT, mapOf("event" to "screen_recording")),
        )

        assertEquals(
            setOf(
                ContextMonitor.CONNECTIVITY,
                ContextMonitor.WIFI,
                ContextMonitor.CAMERA_MIC,
                ContextMonitor.PACKAGE_EVENTS,
                ContextMonitor.BLUETOOTH_EVENTS,
                ContextMonitor.SCREEN_RECORDING,
            ),
            requiredContextMonitors(profile),
        )
    }

    @Test
    fun onlyAContextThatComparesAWifiNameAsksForTheName() {
        fun monitors(vararg config: Pair<String, String>) =
            requiredContextMonitors(profile(1, ContextSpec(ContextType.STATE, mapOf(*config))))
        val both = setOf(ContextMonitor.WIFI_NAME, ContextMonitor.WIFI)
        val connectionOnly = setOf(ContextMonitor.WIFI)

        assertEquals(both, monitors("key" to "wifi", "value" to "Home"))
        assertEquals(both, monitors("predicate" to "ssid=Home"))
        assertEquals(both, monitors("key" to "wifi_ssid", "operator" to "~", "value" to "Office*"))
        // A pattern written as a predicate used to start no monitor at all.
        assertEquals(both, monitors("predicate" to "wifi~Home*"))
        assertEquals(connectionOnly, monitors("key" to "wifi", "value" to "connected"))
        assertEquals(connectionOnly, monitors("key" to "wifi", "value" to "disconnected"))
        assertEquals(connectionOnly, monitors("key" to "wifi_connected", "value" to "true"))
    }

    @Test
    fun theNameDemandComesAndGoesWithTheProfilesThatCompareANameWhileWifiKeepsRunning() {
        val harness = MonitorHarness()
        val connected = profile(1, ContextSpec(ContextType.STATE, mapOf("key" to "wifi_connected", "value" to "true")))
        val atHome = profile(2, ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "value" to "Home")))

        harness.lifecycle.reconcile(listOf(connected))
        assertEquals(listOf("start:WIFI"), harness.events)

        harness.lifecycle.reconcile(listOf(connected, atHome))
        assertEquals(listOf("start:WIFI", "start:WIFI_NAME"), harness.events)

        harness.lifecycle.reconcile(listOf(connected, atHome.copy(enabled = false)))
        assertEquals(listOf("start:WIFI", "start:WIFI_NAME", "stop:WIFI_NAME"), harness.events)
        assertEquals(setOf(ContextMonitor.WIFI), harness.lifecycle.currentlyStarted())
    }

    @Test
    fun broadEventProfileConservativelyOwnsEveryServiceEventMonitor() {
        val broad = profile(1, ContextSpec(ContextType.EVENT, emptyMap()))

        assertEquals(
            setOf(
                ContextMonitor.SHAKE,
                ContextMonitor.CAMERA_MIC,
                ContextMonitor.PACKAGE_EVENTS,
                ContextMonitor.BLUETOOTH_EVENTS,
                ContextMonitor.USB_EVENTS,
                ContextMonitor.COMPANION_EVENTS,
                ContextMonitor.SCREEN_RECORDING,
            ),
            requiredContextMonitors(broad),
        )
    }

    @Test
    fun failedStartRetriesWithoutInventingAnActiveMonitor() {
        var attempts = 0
        val harness = MonitorHarness(
            startOverrides = mapOf(
                ContextMonitor.SHAKE to {
                    attempts += 1
                    attempts > 1
                },
            ),
        )
        val shake = profile(1, ContextSpec(ContextType.EVENT, mapOf("event" to "shake")))

        val first = harness.lifecycle.reconcile(listOf(shake))
        assertEquals(setOf(ContextMonitor.SHAKE), first.failedToStart)
        assertFalse(ContextMonitor.SHAKE in harness.lifecycle.currentlyStarted())

        val second = harness.lifecycle.reconcile(listOf(shake))
        assertEquals(setOf(ContextMonitor.SHAKE), second.started)
        assertTrue(ContextMonitor.SHAKE in harness.lifecycle.currentlyStarted())
        assertEquals(2, attempts)
    }

    @Test
    fun serviceSubscribesMatchersBeforeStartingPulseMonitors() {
        val source = sourceFile("com/opentasker/core/engine/AutomationService.kt").readText()
        val subscription = source.indexOf("scope.launch(start = CoroutineStart.UNDISPATCHED)")
        val subscriptionBarrier = source.indexOf("it.awaitMonitorSubscriptions()")
        val monitorReconcile = source.indexOf("contextMonitorLifecycle.reconcile(profiles)")

        assertTrue("Expected matcher collectors to start undispatched", subscription >= 0)
        assertTrue("Expected an explicit pulse-source subscription barrier", subscriptionBarrier > subscription)
        assertTrue("Monitor producers must start after matcher subscriptions", monitorReconcile > subscriptionBarrier)
    }

    @Test
    fun contextInspectorCannotAcquireTheProductionMonitorLifecycle() {
        val source = sourceFile("com/opentasker/ui/screens/ContextInspectorScreen.kt").readText()

        assertFalse("Inspector must not retain production monitor ownership", "ContextMonitorLifecycle" in source)
        assertFalse("Inspector must not start the app-usage poller", "AppUsageMonitor" in source)
        assertFalse("Inspector must not start the shake sensor", "ShakeDetector" in source)
    }

    @Test
    fun contextInspectorCollectorsFollowVisibleScreenLifecycle() {
        val source = sourceFile("com/opentasker/ui/screens/ContextInspectorScreen.kt").readText()

        assertTrue("Inspector must expose an explicit visible-state start", "fun startObserving()" in source)
        assertTrue("Inspector must stop collectors when the screen leaves composition", "onDispose { viewModel.stopObserving() }" in source)
        assertTrue("Inspector must surface age-aware observation state", "observationStatus(nowMs)" in source)
    }

    private fun profile(id: Long, vararg contexts: ContextSpec): Profile = Profile(
        id = id,
        name = "P$id",
        contexts = contexts.toList(),
        enterTaskId = 1,
    )

    private fun sourceFile(relativePath: String): Path {
        val sourceRoot = listOf(Path.of("src/main/java"), Path.of("app/src/main/java")).first(Files::exists)
        return sourceRoot.resolve(relativePath)
    }

    private class MonitorHarness(
        startOverrides: Map<ContextMonitor, () -> Boolean> = emptyMap(),
    ) {
        val events = mutableListOf<String>()
        val lifecycle = ContextMonitorLifecycle(
            ContextMonitor.entries.associateWith { monitor ->
                ContextMonitorHandle(
                    start = startOverrides[monitor] ?: {
                        events += "start:$monitor"
                        true
                    },
                    stop = { events += "stop:$monitor" },
                )
            },
        )
    }
}
