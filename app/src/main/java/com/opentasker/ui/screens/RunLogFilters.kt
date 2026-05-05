package com.opentasker.ui.screens

import com.opentasker.core.model.RunLogEntry

enum class RunLogStatusFilter(val label: String) {
    All("All"),
    Failed("Failed"),
    Succeeded("Succeeded"),
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
        val statusMatches = when (state.status) {
            RunLogStatusFilter.All -> true
            RunLogStatusFilter.Failed -> !entry.success
            RunLogStatusFilter.Succeeded -> entry.success
        }
        val taskMatches = state.taskId == null || entry.taskId == state.taskId
        val queryMatches = normalizedQuery.isBlank() ||
            entry.taskName.contains(normalizedQuery, ignoreCase = true) ||
            entry.message.contains(normalizedQuery, ignoreCase = true)

        statusMatches && taskMatches && queryMatches
    }
}
