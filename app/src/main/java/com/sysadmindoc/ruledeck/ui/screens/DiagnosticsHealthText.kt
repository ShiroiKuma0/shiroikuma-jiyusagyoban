package com.sysadmindoc.ruledeck.ui.screens

import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthReader
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthStatus
import com.sysadmindoc.ruledeck.core.diagnostics.HealthSignal
import com.sysadmindoc.ruledeck.core.diagnostics.HealthSignalState
import com.sysadmindoc.ruledeck.core.diagnostics.PendingJobEntry
import com.sysadmindoc.ruledeck.core.diagnostics.ScheduledJobDiagnostics
import com.sysadmindoc.ruledeck.core.diagnostics.decidingSignal
import com.sysadmindoc.ruledeck.core.engine.EngineExitCorrelation
import com.sysadmindoc.ruledeck.core.engine.EngineExitCorrelationState
import com.sysadmindoc.ruledeck.core.scheduling.AlarmSchedulePrecision
import java.text.SimpleDateFormat
import java.util.Date

// Diagnostics words what EngineHealthReader measured in the app's language. The reader's own text
// stays English for the shared report, so a bug report reads the same from every phone (A-438).

@StringRes
internal fun healthSignalLabel(key: String): Int? = when (key) {
    "engine" -> R.string.diagnostics_service
    "matchers" -> R.string.diagnostics_signal_matchers
    "standby" -> R.string.diagnostics_signal_standby
    "exact-alarm" -> R.string.diagnostics_signal_exact_alarm
    "watchdog" -> R.string.diagnostics_worker_engine_watchdog
    "scheduled-jobs" -> R.string.diagnostics_signal_scheduled_jobs
    "process-exit" -> R.string.diagnostics_process_exit
    "advanced-protection" -> R.string.diagnostics_advanced_protection
    "executions" -> R.string.diagnostics_signal_executions
    else -> null
}

@Composable
internal fun healthSignalLabelText(signal: HealthSignal): String =
    healthSignalLabel(signal.key)?.let { stringResource(it) } ?: signal.label

/** One evidence line: the signal's name, its state and why. */
@Composable
internal fun healthSignalText(signal: HealthSignal, health: EngineHealthStatus, formatter: SimpleDateFormat): String =
    stringResource(
        R.string.diagnostics_health_signal,
        healthSignalLabelText(signal),
        stringResource(signal.state.resourceId),
        healthSignalReason(signal, health, formatter),
    )

/** The summary card's line under the overall status, from the same signal `assessHealth` picks. */
@Composable
internal fun healthAssessmentText(health: EngineHealthStatus, formatter: SimpleDateFormat): String {
    val signal = decidingSignal(health.signals) ?: return stringResource(R.string.diagnostics_assessment_all_current)
    return stringResource(
        R.string.diagnostics_assessment_reason,
        healthSignalLabelText(signal),
        healthSignalReason(signal, health, formatter),
    )
}

@Composable
private fun healthSignalReason(signal: HealthSignal, health: EngineHealthStatus, formatter: SimpleDateFormat): String =
    when (signal.key) {
        "engine" -> engineReason(signal.state, health)
        // A matcher error is the exception's own message, like a log line.
        "matchers" -> health.lastMatcherError ?: if (signal.state == HealthSignalState.Ready) {
            stringResource(R.string.diagnostics_signal_matchers_ready)
        } else {
            engineReason(signal.state, health)
        }
        "standby" -> stringResource(standbyConsequenceRes(health.standbyBucketCode))
        "exact-alarm" -> stringResource(
            if (signal.state == HealthSignalState.Ready) R.string.diagnostics_signal_exact_alarm_ready
            else R.string.diagnostics_signal_exact_alarm_inexact,
        )
        "watchdog" -> health.lastWorkerStopReasonCode?.let { stopReasonSentence(it) }
            ?: stringResource(R.string.diagnostics_signal_watchdog_none)
        "scheduled-jobs" -> scheduledJobsReason(health.pendingScheduledJobs)
        "process-exit" -> processExitSummary(health.processExitCorrelation, formatter)
        "advanced-protection" -> stringResource(
            if (health.advancedProtectionEnabled) R.string.diagnostics_signal_advanced_protection_on
            else R.string.diagnostics_signal_advanced_protection_off,
        )
        "executions" -> stringResource(
            R.string.diagnostics_signal_executions_count,
            health.pendingExecutionCount,
            health.activeExecutionCount,
        )
        else -> signal.reason
    }

@Composable
private fun engineReason(state: HealthSignalState, health: EngineHealthStatus): String = when (state) {
    HealthSignalState.Loading -> stringResource(R.string.diagnostics_signal_engine_loading)
    HealthSignalState.Stale -> stringResource(
        R.string.diagnostics_signal_engine_stale,
        durationText(health.readAtMillis - health.lastHeartbeatAtMillis),
    )
    else -> stringResource(R.string.diagnostics_signal_engine_ready)
}

@Composable
private fun scheduledJobsReason(jobs: ScheduledJobDiagnostics): String = stringResource(
    when {
        !jobs.currentAvailable -> R.string.diagnostics_pending_jobs_unavailable
        jobs.currentEntries.isNotEmpty() -> R.string.diagnostics_signal_scheduled_jobs_blocked
        else -> R.string.diagnostics_pending_jobs_none
    },
)

/** A stop reason on a line of its own; the resources are worded to follow "Stopped by Android: ". */
@Composable
internal fun stopReasonSentence(reason: Int): String =
    stringResource(stopReasonLabel(reason)).replaceFirstChar { it.titlecase() }

@StringRes
internal fun exactAlarmRes(precision: AlarmSchedulePrecision?): Int = when (precision) {
    AlarmSchedulePrecision.Exact -> R.string.diagnostics_exact_alarm_exact
    AlarmSchedulePrecision.InexactFallback -> R.string.diagnostics_exact_alarm_inexact
    null -> R.string.diagnostics_none
}

@StringRes
internal fun standbyConsequenceRes(bucket: Int?): Int = when (bucket) {
    null -> R.string.diagnostics_standby_unavailable
    UsageStatsManager.STANDBY_BUCKET_ACTIVE -> R.string.diagnostics_standby_active
    UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> R.string.diagnostics_standby_working_set
    UsageStatsManager.STANDBY_BUCKET_FREQUENT -> R.string.diagnostics_standby_frequent
    UsageStatsManager.STANDBY_BUCKET_RARE -> R.string.diagnostics_standby_rare
    UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> R.string.diagnostics_standby_restricted
    else -> R.string.diagnostics_standby_unknown
}

@Composable
internal fun foregroundServiceTypesText(bits: Int): String {
    if (bits == 0) return stringResource(R.string.diagnostics_none)
    val names = buildList {
        if (bits and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) add(stringResource(R.string.diagnostics_fgs_type_special_use))
        if (bits and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add(stringResource(R.string.diagnostics_fgs_type_location))
    }
    if (names.isEmpty()) return stringResource(R.string.diagnostics_fgs_type_unknown, bits)
    return names.joinToString(stringResource(R.string.diagnostics_list_separator))
}

@StringRes
internal fun pendingJobReasonRes(reason: Int): Int? = when (reason) {
    JobScheduler.PENDING_JOB_REASON_APP -> R.string.diagnostics_pending_reason_app
    JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> R.string.diagnostics_pending_reason_app_standby
    JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> R.string.diagnostics_pending_reason_background_restriction
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> R.string.diagnostics_pending_reason_battery_not_low
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> R.string.diagnostics_pending_reason_charging
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> R.string.diagnostics_pending_reason_connectivity
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> R.string.diagnostics_pending_reason_content_trigger
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> R.string.diagnostics_pending_reason_device_idle
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> R.string.diagnostics_pending_reason_minimum_latency
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH -> R.string.diagnostics_pending_reason_prefetch
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> R.string.diagnostics_pending_reason_storage_not_low
    JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> R.string.diagnostics_pending_reason_device_state
    JobScheduler.PENDING_JOB_REASON_QUOTA -> R.string.diagnostics_pending_reason_quota
    JobScheduler.PENDING_JOB_REASON_USER -> R.string.diagnostics_pending_reason_user
    JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEADLINE -> R.string.diagnostics_pending_reason_deadline
    else -> null
}

@Composable
private fun pendingJobReasonText(reason: Int): String =
    pendingJobReasonRes(reason)?.let { stringResource(it) } ?: stringResource(R.string.diagnostics_reason_code, reason)

/** One line per entry; history entries say how long ago, stats entries for how long. */
@Composable
internal fun pendingJobEntriesText(entries: List<PendingJobEntry>): String = entries.map { entry ->
    val reason = pendingJobReasonText(entry.reason)
    val ageMillis = entry.ageMillis
    val durationMillis = entry.durationMillis
    when {
        ageMillis != null -> stringResource(R.string.diagnostics_pending_job_history_entry, entry.jobId, durationText(ageMillis), reason)
        durationMillis != null -> stringResource(R.string.diagnostics_pending_job_stats_entry, entry.jobId, reason, durationText(durationMillis))
        else -> stringResource(R.string.diagnostics_pending_job_entry, entry.jobId, reason)
    }
}.joinToString("\n")

@Composable
internal fun durationText(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    return when {
        seconds < 1L -> stringResource(R.string.diagnostics_duration_under_second)
        seconds < 60L -> stringResource(R.string.diagnostics_duration_seconds, seconds)
        seconds < 3_600L -> stringResource(R.string.diagnostics_duration_minutes_seconds, seconds / 60L, seconds % 60L)
        else -> stringResource(R.string.diagnostics_duration_hours_minutes, seconds / 3_600L, (seconds % 3_600L) / 60L)
    }
}

private val exitReasonStrings = listOf(
    ApplicationExitInfo.REASON_ANR to R.string.diagnostics_exit_reason_anr,
    ApplicationExitInfo.REASON_CRASH to R.string.diagnostics_exit_reason_crash,
    ApplicationExitInfo.REASON_CRASH_NATIVE to R.string.diagnostics_exit_reason_crash_native,
    ApplicationExitInfo.REASON_DEPENDENCY_DIED to R.string.diagnostics_exit_reason_dependency_died,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to R.string.diagnostics_exit_reason_excessive_resource_usage,
    ApplicationExitInfo.REASON_EXIT_SELF to R.string.diagnostics_exit_reason_exit_self,
    ApplicationExitInfo.REASON_FREEZER to R.string.diagnostics_exit_reason_freezer,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to R.string.diagnostics_exit_reason_initialization_failure,
    ApplicationExitInfo.REASON_LOW_MEMORY to R.string.diagnostics_exit_reason_low_memory,
    ApplicationExitInfo.REASON_OTHER to R.string.diagnostics_exit_reason_other,
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to R.string.diagnostics_exit_reason_package_state_change,
    ApplicationExitInfo.REASON_PACKAGE_UPDATED to R.string.diagnostics_exit_reason_package_updated,
    ApplicationExitInfo.REASON_PERMISSION_CHANGE to R.string.diagnostics_exit_reason_permission_change,
    ApplicationExitInfo.REASON_SIGNALED to R.string.diagnostics_exit_reason_signaled,
    ApplicationExitInfo.REASON_USER_REQUESTED to R.string.diagnostics_exit_reason_user_requested,
    ApplicationExitInfo.REASON_USER_STOPPED to R.string.diagnostics_exit_reason_user_stopped,
)

private val unlistedExitReason = Regex("""Reason (-?\d+)""")

/**
 * The exit reason is kept as the reader's English label (it is stored with the heartbeat), so it is
 * matched back to its code here. A label from an older build that matches nothing shows as stored.
 */
@StringRes
internal fun exitReasonRes(label: String): Int? =
    exitReasonStrings.firstOrNull { (code, _) -> EngineHealthReader.applicationExitReasonLabel(code) == label }?.second

@Composable
internal fun exitReasonText(label: String?): String {
    if (label == null) return stringResource(R.string.diagnostics_none)
    exitReasonRes(label)?.let { return stringResource(it) }
    unlistedExitReason.matchEntire(label)?.let { return stringResource(R.string.diagnostics_reason_code, it.groupValues[1].toInt()) }
    return label
}

@Composable
internal fun processExitSummary(correlation: EngineExitCorrelation, formatter: SimpleDateFormat): String = when (correlation.state) {
    EngineExitCorrelationState.UNAVAILABLE -> stringResource(R.string.diagnostics_process_exit_unavailable)
    EngineExitCorrelationState.NO_GAP -> stringResource(R.string.diagnostics_process_exit_no_gap)
    EngineExitCorrelationState.NO_MATCH -> stringResource(
        R.string.diagnostics_process_exit_no_match,
        correlation.gapMillis?.let { durationText(it) } ?: stringResource(R.string.diagnostics_none),
    )
    EngineExitCorrelationState.MATCHED -> stringResource(
        R.string.diagnostics_process_exit_matched,
        exitReasonText(correlation.reason),
        correlation.description ?: stringResource(R.string.diagnostics_none),
        correlation.timestampMillis?.let { formatter.format(Date(it)) } ?: stringResource(R.string.diagnostics_never),
        correlation.gapMillis?.let { durationText(it) } ?: stringResource(R.string.diagnostics_none),
    )
}
