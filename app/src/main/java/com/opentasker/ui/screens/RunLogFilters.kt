package com.opentasker.ui.screens

import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogStatusQuery
import com.opentasker.core.storage.escapeRunLogLikeQuery

enum class RunLogStatusFilter(val label: String) {
    All("All"),
    Succeeded("Succeeded"),
    Failed("Failed"),
    Skipped("Skipped"),
    Cancelled("Cancelled"),
}

enum class RunLogDateFilter(val label: String, val ageMillis: Long?) {
    All("Any date", null),
    Day("24 hours", 24L * 60 * 60 * 1_000),
    Week("7 days", 7L * 24 * 60 * 60 * 1_000),
    Month("30 days", 30L * 24 * 60 * 60 * 1_000),
}

data class RunLogFilterState(
    val status: RunLogStatusFilter = RunLogStatusFilter.All,
    val taskId: Long? = null,
    val query: String = "",
    val date: RunLogDateFilter = RunLogDateFilter.All,
)

fun RunLogFilterState.toStorageQuery(nowMillis: Long = System.currentTimeMillis()): RunLogQuery = RunLogQuery(
    status = when (status) {
        RunLogStatusFilter.All -> RunLogStatusQuery.ALL
        RunLogStatusFilter.Succeeded -> RunLogStatusQuery.SUCCEEDED
        RunLogStatusFilter.Failed -> RunLogStatusQuery.FAILED
        RunLogStatusFilter.Skipped -> RunLogStatusQuery.SKIPPED
        RunLogStatusFilter.Cancelled -> RunLogStatusQuery.CANCELLED
    },
    taskId = taskId,
    minimumTimestamp = date.ageMillis?.let { nowMillis - it },
    escapedSearch = escapeRunLogLikeQuery(query),
)
