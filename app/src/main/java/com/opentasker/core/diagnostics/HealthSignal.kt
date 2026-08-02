package com.opentasker.core.diagnostics

enum class HealthSignalState {
    Loading,
    Ready,
    Stale,
    Error,
}

/** A single timestamped piece of health evidence used by both the summary and detail views. */
data class HealthSignal(
    val key: String,
    val label: String,
    val state: HealthSignalState,
    val observedAtMillis: Long,
    val reason: String,
    val required: Boolean = true,
)

data class HealthAssessment(
    val state: HealthSignalState,
    val reason: String,
) {
    val healthy: Boolean
        get() = state == HealthSignalState.Ready
}

fun assessHealth(signals: List<HealthSignal>): HealthAssessment {
    val required = signals.filter { it.required }
    val failed = required.firstOrNull { it.state == HealthSignalState.Error }
    if (failed != null) return HealthAssessment(HealthSignalState.Error, "${failed.label}: ${failed.reason}")
    val stale = required.firstOrNull { it.state == HealthSignalState.Stale }
    if (stale != null) return HealthAssessment(HealthSignalState.Stale, "${stale.label}: ${stale.reason}")
    val loading = required.firstOrNull { it.state == HealthSignalState.Loading }
    if (loading != null) return HealthAssessment(HealthSignalState.Loading, "${loading.label}: ${loading.reason}")
    return HealthAssessment(HealthSignalState.Ready, "All required health evidence is current.")
}
