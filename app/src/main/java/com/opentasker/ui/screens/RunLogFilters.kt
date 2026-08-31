package com.opentasker.ui.screens

import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.outcome

enum class RunLogStatusFilter(val label: String) {
    All("All"),
    Succeeded("Succeeded"),
    Failed("Failed"),
    Skipped("Skipped"),
}

data class RunLogFilterState(
    val status: RunLogStatusFilter = RunLogStatusFilter.All,
    val taskId: Long? = null,
    val query: String = "",
)

fun filterRunLogs(
    logs: List<RunLogEntry>,
    state: RunLogFilterState,
): List<RunLogEntry> {
    val normalizedQuery = state.query.trim()
    return logs.filter { entry ->
        val outcome = entry.outcome()
        val statusMatches = when (state.status) {
            RunLogStatusFilter.All -> true
            RunLogStatusFilter.Failed -> outcome == RunLogOutcome.Failed
            RunLogStatusFilter.Succeeded -> outcome == RunLogOutcome.Succeeded
            RunLogStatusFilter.Skipped -> outcome == RunLogOutcome.Skipped
        }
        val taskMatches = state.taskId == null || entry.taskId == state.taskId
        val queryMatches = normalizedQuery.isBlank() ||
            entry.taskName.contains(normalizedQuery, ignoreCase = true) ||
            entry.message.contains(normalizedQuery, ignoreCase = true)

        statusMatches && taskMatches && queryMatches
    }
}
