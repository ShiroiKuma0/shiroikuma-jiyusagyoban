package com.opentasker.core.engine

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import java.util.Locale

/**
 * OS-backed context producers owned by [AutomationService]. Context source flows that are scoped
 * directly to a profile matcher (time, location, state broadcasts, and plugins) are intentionally
 * absent: cancelling the matcher already releases those resources.
 */
internal enum class ContextMonitor {
    WIFI,
    CONNECTIVITY,
    APP_USAGE,
    SHAKE,
    CAMERA_MIC,
    PACKAGE_EVENTS,
    BLUETOOTH_EVENTS,
    USB_EVENTS,
    COMPANION_EVENTS,
}

internal data class ContextMonitorHandle(
    val start: () -> Boolean,
    val stop: () -> Unit,
)

internal data class ContextMonitorTransition(
    val started: Set<ContextMonitor> = emptySet(),
    val stopped: Set<ContextMonitor> = emptySet(),
    val failedToStart: Set<ContextMonitor> = emptySet(),
    val failedToStop: Set<ContextMonitor> = emptySet(),
)

/**
 * Reconciles production monitors against enabled-profile demand. Reference counts are profile
 * counts, not raw context counts: two Wi-Fi predicates in one profile still represent one owner.
 * A monitor starts on the first owner and stops after the last owner disappears.
 */
internal class ContextMonitorLifecycle(
    private val handles: Map<ContextMonitor, ContextMonitorHandle>,
) {
    private var referenceCounts: Map<ContextMonitor, Int> = emptyMap()
    private val startedMonitors = mutableSetOf<ContextMonitor>()

    init {
        require(handles.keys == ContextMonitor.entries.toSet()) {
            "A lifecycle handle is required for every context monitor"
        }
    }

    @Synchronized
    fun reconcile(profiles: Collection<Profile>): ContextMonitorTransition =
        reconcile(contextMonitorReferenceCounts(profiles))

    @Synchronized
    fun stopAll(): ContextMonitorTransition = reconcile(emptyMap())

    @Synchronized
    fun currentReferenceCounts(): Map<ContextMonitor, Int> = referenceCounts.toMap()

    @Synchronized
    fun currentlyStarted(): Set<ContextMonitor> = startedMonitors.toSet()

    private fun reconcile(requiredCounts: Map<ContextMonitor, Int>): ContextMonitorTransition {
        val normalizedCounts = requiredCounts.filterValues { it > 0 }
        val started = mutableSetOf<ContextMonitor>()
        val stopped = mutableSetOf<ContextMonitor>()
        val failedToStart = mutableSetOf<ContextMonitor>()
        val failedToStop = mutableSetOf<ContextMonitor>()

        // Matchers subscribe before this method is called. Start new pulse producers first so their
        // first event cannot race ahead of the newly enabled profile collector.
        ContextMonitor.entries.forEach { monitor ->
            if (normalizedCounts.getOrDefault(monitor, 0) > 0 && monitor !in startedMonitors) {
                val didStart = runCatching { handles.getValue(monitor).start() }.getOrDefault(false)
                if (didStart) {
                    startedMonitors += monitor
                    started += monitor
                } else {
                    failedToStart += monitor
                }
            }
        }

        ContextMonitor.entries.forEach { monitor ->
            if (normalizedCounts.getOrDefault(monitor, 0) == 0 && monitor in startedMonitors) {
                val didStop = runCatching { handles.getValue(monitor).stop() }.isSuccess
                if (didStop) {
                    startedMonitors -= monitor
                    stopped += monitor
                } else {
                    failedToStop += monitor
                }
            }
        }

        referenceCounts = normalizedCounts
        return ContextMonitorTransition(started, stopped, failedToStart, failedToStop)
    }
}

internal fun contextMonitorReferenceCounts(profiles: Collection<Profile>): Map<ContextMonitor, Int> {
    val counts = mutableMapOf<ContextMonitor, Int>()
    profiles.asSequence()
        .filter { it.enabled && !it.requiresRiskAcknowledgement }
        .forEach { profile ->
            requiredContextMonitors(profile).forEach { monitor ->
                counts[monitor] = counts.getOrDefault(monitor, 0) + 1
            }
        }
    return counts
}

internal fun requiredContextMonitors(profile: Profile): Set<ContextMonitor> = buildSet {
    profile.contexts.forEach { spec ->
        when (spec.type) {
            ContextType.APPLICATION -> add(ContextMonitor.APP_USAGE)
            ContextType.STATE -> addStateMonitor(spec)
            ContextType.EVENT -> addEventMonitor(spec)
            ContextType.TIME,
            ContextType.DAY,
            ContextType.LOCATION,
            ContextType.PLUGIN,
            -> Unit
        }
    }
}

private fun MutableSet<ContextMonitor>.addStateMonitor(spec: ContextSpec) {
    when (spec.stateKey()) {
        "wifi", "ssid", "wifi_ssid", "wifi_connected" -> add(ContextMonitor.WIFI)
        "internet", "network_type", "vpn" -> add(ContextMonitor.CONNECTIVITY)
    }
}

private fun MutableSet<ContextMonitor>.addEventMonitor(spec: ContextSpec) {
    when (val event = spec.config["event"].orEmpty().trim().lowercase(Locale.US)) {
        "shake" -> add(ContextMonitor.SHAKE)
        "camera", "mic", "microphone" -> add(ContextMonitor.CAMERA_MIC)
        "package_added", "package_removed", "package_replaced" -> add(ContextMonitor.PACKAGE_EVENTS)
        "bluetooth" -> add(ContextMonitor.BLUETOOTH_EVENTS)
        "usb" -> add(ContextMonitor.USB_EVENTS)
        "companion_presence" -> add(ContextMonitor.COMPANION_EVENTS)
        "" -> addAll(EVENT_MONITORS)
        else -> Unit
    }
}

private fun ContextSpec.stateKey(): String {
    val predicate = config["predicate"].orEmpty().trim()
    if (predicate.isNotEmpty()) {
        val operatorIndex = predicate.indexOfAny(charArrayOf('=', '<', '>'))
        if (operatorIndex > 0) return predicate.substring(0, operatorIndex).trim().lowercase(Locale.US)
    }
    return config["key"].orEmpty().trim().lowercase(Locale.US)
}

private val EVENT_MONITORS = setOf(
    ContextMonitor.SHAKE,
    ContextMonitor.CAMERA_MIC,
    ContextMonitor.PACKAGE_EVENTS,
    ContextMonitor.BLUETOOTH_EVENTS,
    ContextMonitor.USB_EVENTS,
    ContextMonitor.COMPANION_EVENTS,
)
