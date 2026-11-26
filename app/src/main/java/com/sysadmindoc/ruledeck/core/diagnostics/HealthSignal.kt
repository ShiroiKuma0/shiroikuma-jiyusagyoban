package com.sysadmindoc.ruledeck.core.diagnostics

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

/** The required signal the overall state comes from: the first Error, else Stale, else Loading. */
fun decidingSignal(signals: List<HealthSignal>): HealthSignal? {
    val required = signals.filter { it.required }
    return required.firstOrNull { it.state == HealthSignalState.Error }
        ?: required.firstOrNull { it.state == HealthSignalState.Stale }
        ?: required.firstOrNull { it.state == HealthSignalState.Loading }
}

fun assessHealth(signals: List<HealthSignal>): HealthAssessment {
    val deciding = decidingSignal(signals)
        ?: return HealthAssessment(HealthSignalState.Ready, "All required health evidence is current.")
    return HealthAssessment(deciding.state, "${deciding.label}: ${deciding.reason}")
}
