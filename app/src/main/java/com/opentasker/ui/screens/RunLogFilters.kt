package com.opentasker.ui.screens

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogStatusQuery
import com.opentasker.core.storage.escapeRunLogLikeQuery

enum class RunLogStatusFilter(@StringRes val labelRes: Int) {
    All(R.string.run_log_filter_all),
    Succeeded(R.string.run_log_filter_succeeded),
    Failed(R.string.run_log_filter_failed),
    Skipped(R.string.run_log_filter_skipped),
    Held(R.string.run_log_filter_held),
    Cancelled(R.string.run_log_filter_cancelled),
}

enum class RunLogDateFilter(@StringRes val labelRes: Int, val ageMillis: Long?) {
    All(R.string.run_log_date_any, null),
    Day(R.string.run_log_date_day, 24L * 60 * 60 * 1_000),
    Week(R.string.run_log_date_week, 7L * 24 * 60 * 60 * 1_000),
    Month(R.string.run_log_date_month, 30L * 24 * 60 * 60 * 1_000),
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
        RunLogStatusFilter.Held -> RunLogStatusQuery.HELD
        RunLogStatusFilter.Cancelled -> RunLogStatusQuery.CANCELLED
    },
    taskId = taskId,
    minimumTimestamp = date.ageMillis?.let { nowMillis - it },
    escapedSearch = escapeRunLogLikeQuery(query),
)
