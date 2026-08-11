package com.opentasker.ui.screens

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import com.opentasker.core.storage.RunLogStatusQuery
import com.opentasker.core.storage.escapeRunLogLikeQuery

enum class RunLogStatusFilter(@StringRes val labelRes: Int) {
    All(R.string.run_log_filter_all),
    Succeeded(R.string.run_log_filter_succeeded),
    Failed(R.string.run_log_filter_failed),
    Skipped(R.string.run_log_filter_skipped),
    Held(R.string.run_log_filter_held),
    Cancelled(R.string.run_log_filter_cancelled),
    Interrupted(R.string.run_log_filter_interrupted),
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
        RunLogStatusFilter.Interrupted -> RunLogStatusQuery.INTERRUPTED
    },
    taskId = taskId,
    minimumTimestamp = date.ageMillis?.let { nowMillis - it },
    escapedSearch = escapeRunLogLikeQuery(query),
)

data class RunLogPageUiState(
    val entries: ImmutableList<RunLogEntry> = persistentListOf(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    /**
     * Set when the last load failed. Without it a failed refresh was indistinguishable from an
     * empty result - the screen showed "no runs match" and the only report was a 4-second
     * snackbar - so the reader concluded their filters were wrong rather than that the read broke.
     */
    val failed: Boolean = false,
    internal val snapshot: RunLogSnapshot? = null,
)

data class RunLogRetentionPreview(
    val policy: RunLogRetentionPolicy,
    val storedCount: Int,
    val prunableCount: Int,
    val oldestTimestamp: Long?,
)
