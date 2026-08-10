package com.opentasker.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opentasker.core.engine.EngineHeartbeatStore
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.EngineExitCorrelation
import com.opentasker.core.engine.EngineExitCorrelationState
import com.opentasker.core.engine.HistoricalProcessExit
import com.opentasker.core.engine.correlateProcessExit
import com.opentasker.core.engine.needsRecovery
import com.opentasker.core.external.ExternalExecutions
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport
import kotlinx.coroutines.flow.first

data class EngineHealthStatus(
    val serviceRunning: Boolean,
    val lastHeartbeatAtMillis: Long,
    val activeForegroundServiceTypes: String,
    val standbyBucket: String,
    /** True when the app-standby bucket (RARE/RESTRICTED) throttles the per-minute alarm/workers. */
    val standbyThrottled: Boolean,
    /** True when Android 16 Advanced Protection Mode is active and may degrade automation features. */
    val advancedProtectionEnabled: Boolean,
    val exactAlarmStatus: String,
    val lastMatcherError: String?,
    val lastMatcherErrorAtMillis: Long,
    val lastWorkerStopReason: String?,
    val processExitCorrelation: EngineExitCorrelation = EngineExitCorrelation(EngineExitCorrelationState.UNAVAILABLE),
    /**
     * Plain-language reasons this app's scheduled jobs (WorkManager watchdog/prune) are still
     * pending — e.g. "App standby, Connectivity". Null when nothing is blocked or the signal is
     * unavailable (below Android 14). Answers the "why hasn't my scheduled automation fired" case.
     */
    val pendingScheduledJobReasons: String? = null,
    val activeExecutionCount: Int = 0,
    val pendingExecutionCount: Int = 0,
    val signals: List<HealthSignal> = emptyList(),
)

data class HistoricalProcessExitRead(
    val platformAvailable: Boolean,
    val records: List<HistoricalProcessExit>,
)

fun interface HistoricalProcessExitSource {
    fun read(context: Context): HistoricalProcessExitRead
}

private object AndroidHistoricalProcessExitSource : HistoricalProcessExitSource {
    override fun read(context: Context): HistoricalProcessExitRead {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        }
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        return runCatching {
            HistoricalProcessExitRead(
                platformAvailable = true,
                records = activityManager
                    .getHistoricalProcessExitReasons(context.packageName, 0, EngineHealthReader.MAX_HISTORICAL_EXITS)
                    .orEmpty()
                    .map { exit ->
                        HistoricalProcessExit(
                            timestampMillis = exit.timestamp,
                            reason = EngineHealthReader.applicationExitReasonLabel(exit.reason),
                            description = exit.description
                                ?.replace(Regex("[\\r\\n]+"), " ")
                                ?.trim()
                                ?.take(EngineHealthReader.MAX_EXIT_DESCRIPTION_CHARS)
                                ?.takeIf(String::isNotBlank),
                        )
                    },
            )
        }.getOrElse {
            HistoricalProcessExitRead(platformAvailable = false, records = emptyList())
        }
    }
}

val EngineHealthStatus.assessment: HealthAssessment
    get() = assessHealth(signals)

val EngineHealthStatus.healthy: Boolean
    get() = assessment.healthy

object EngineHealthReader {
    suspend fun read(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        processExitSource: HistoricalProcessExitSource = AndroidHistoricalProcessExitSource,
    ): EngineHealthStatus {
        val heartbeatStore = EngineHeartbeatStore(context)
        val persisted = heartbeatStore.readPersistedHealth()
        val processExitCorrelation = persisted.processExitCorrelation ?: correlateProcessExit(
            heartbeat = persisted.heartbeat,
            nowMillis = nowMillis,
            read = processExitSource.read(context),
        )
        val workerStopReason = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(EngineWatchdogWorker.WORK_NAME)
                .first()
                .firstOrNull()
                ?.stopReason
        }.getOrNull()
        val pendingReasons = readPendingScheduledJobReasons(context)
        val activeExecutionCount = ActiveExecutionRegistry.active.value.size
        val pendingExecutionCount = ExternalExecutions.snapshot(context)
            .count { !it.state.isTerminal }
        val serviceState = when {
            persisted.heartbeat.lastAliveAtMillis <= 0L -> HealthSignalState.Loading
            persisted.heartbeat.needsRecovery(nowMillis) -> HealthSignalState.Stale
            else -> HealthSignalState.Ready
        }
        val serviceReason = when (serviceState) {
            HealthSignalState.Loading -> "The engine has not published its first heartbeat."
            HealthSignalState.Stale -> "Last heartbeat was ${ageLabel(nowMillis - persisted.heartbeat.lastAliveAtMillis)} ago."
            else -> "Heartbeat is current."
        }
        val matcherState = if (persisted.lastMatcherError != null) HealthSignalState.Error else serviceState
        val matcherReason = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive)
            ?: if (matcherState == HealthSignalState.Ready) "Profile matchers are reporting normally." else serviceReason
        val standbyState = if (standbyBucketThrottled(context)) HealthSignalState.Stale else HealthSignalState.Ready
        val exactAlarm = ExactAlarmSupport.schedulePrecision(context)
        val exactAlarmState = if (exactAlarm == AlarmSchedulePrecision.Exact) {
            HealthSignalState.Ready
        } else {
            HealthSignalState.Stale
        }
        val watchdogState = when {
            workerStopReason == null || workerStopReason == WorkInfo.STOP_REASON_NOT_STOPPED -> HealthSignalState.Ready
            else -> HealthSignalState.Error
        }
        val scheduledState = if (pendingReasons == null) HealthSignalState.Ready else HealthSignalState.Stale
        val processExitState = when (processExitCorrelation.state) {
            EngineExitCorrelationState.MATCHED,
            EngineExitCorrelationState.NO_MATCH,
            -> HealthSignalState.Stale
            EngineExitCorrelationState.UNAVAILABLE -> HealthSignalState.Loading
            EngineExitCorrelationState.NO_GAP -> HealthSignalState.Ready
        }
        val signals = listOf(
            HealthSignal("engine", "Automation service", serviceState, persisted.heartbeat.lastAliveAtMillis, serviceReason),
            HealthSignal("matchers", "Profile matchers", matcherState, persisted.lastMatcherErrorAtMillis.takeIf { it > 0 } ?: persisted.heartbeat.lastAliveAtMillis, matcherReason),
            HealthSignal(
                "standby",
                "App standby",
                standbyState,
                nowMillis,
                if (standbyState == HealthSignalState.Ready) "${standbyBucketLabel(context)} does not currently throttle delivery." else "${standbyBucketLabel(context)} may delay alarms and workers.",
            ),
            HealthSignal(
                "exact-alarm",
                "Time alarms",
                exactAlarmState,
                nowMillis,
                if (exactAlarmState == HealthSignalState.Ready) "Exact alarm delivery is available." else "Exact alarms are unavailable; using the inexact Doze fallback.",
            ),
            HealthSignal(
                "watchdog",
                "Watchdog worker",
                watchdogState,
                nowMillis,
                workerStopReason?.let(::workerStopReasonLabel) ?: "No worker stop failure is recorded.",
            ),
            HealthSignal(
                "scheduled-jobs",
                "Scheduled jobs",
                scheduledState,
                nowMillis,
                pendingReasons?.let { "Blocked by $it." } ?: "No pending scheduler constraints are reported.",
            ),
            HealthSignal(
                "process-exit",
                "Process exit correlation",
                processExitState,
                processExitCorrelation.timestampMillis ?: nowMillis,
                processExitReason(processExitCorrelation),
                required = false,
            ),
            HealthSignal(
                "advanced-protection",
                "Advanced Protection",
                if (AdvancedProtectionReader.isEnabled(context)) HealthSignalState.Stale else HealthSignalState.Ready,
                nowMillis,
                if (AdvancedProtectionReader.isEnabled(context)) "Android Advanced Protection is enabled and may limit privileged extensions." else "Advanced Protection is not limiting this app.",
                required = false,
            ),
            HealthSignal(
                "executions",
                "Pending executions",
                HealthSignalState.Ready,
                nowMillis,
                "$pendingExecutionCount external and $activeExecutionCount active execution(s) are tracked.",
                required = false,
            ),
        )
        return EngineHealthStatus(
            serviceRunning = serviceState == HealthSignalState.Ready,
            lastHeartbeatAtMillis = persisted.heartbeat.lastAliveAtMillis,
            activeForegroundServiceTypes = foregroundServiceTypeLabel(persisted.heartbeat.foregroundServiceTypes),
            standbyBucket = standbyBucketLabel(context),
            standbyThrottled = standbyBucketThrottled(context),
            advancedProtectionEnabled = AdvancedProtectionReader.isEnabled(context),
            exactAlarmStatus = when (exactAlarm) {
                AlarmSchedulePrecision.Exact -> "Exact allowed"
                AlarmSchedulePrecision.InexactFallback -> "Inexact Doze fallback"
            },
            lastMatcherError = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive),
            lastMatcherErrorAtMillis = persisted.lastMatcherErrorAtMillis,
            lastWorkerStopReason = workerStopReason?.let(::workerStopReasonLabel),
            processExitCorrelation = processExitCorrelation,
            pendingScheduledJobReasons = pendingReasons,
            activeExecutionCount = activeExecutionCount,
            pendingExecutionCount = pendingExecutionCount,
            signals = signals,
        )
    }

    /** Capture the previous process lifetime before AutomationService overwrites its heartbeat. */
    internal fun captureStartupProcessExitCorrelation(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        processExitSource: HistoricalProcessExitSource = AndroidHistoricalProcessExitSource,
    ) {
        val store = EngineHeartbeatStore(context)
        val persisted = store.readPersistedHealth()
        store.recordProcessExitCorrelation(
            correlateProcessExit(
                heartbeat = persisted.heartbeat,
                nowMillis = nowMillis,
                read = processExitSource.read(context),
            ),
        )
    }

    internal fun correlateProcessExit(
        heartbeat: com.opentasker.core.engine.EngineHeartbeat,
        nowMillis: Long,
        read: HistoricalProcessExitRead,
        staleAfterMillis: Long = EngineHeartbeatStore.STALE_AFTER_MS,
    ): EngineExitCorrelation = correlateProcessExit(
        heartbeat = heartbeat,
        nowMillis = nowMillis,
        platformAvailable = read.platformAvailable,
        exits = read.records,
        staleAfterMillis = staleAfterMillis,
    )

    internal fun processExitReason(correlation: EngineExitCorrelation): String = when (correlation.state) {
        EngineExitCorrelationState.UNAVAILABLE ->
            "ApplicationExitInfo is unavailable below Android 11 (API 30) or could not be read."
        EngineExitCorrelationState.NO_GAP ->
            "No unexpected heartbeat gap is pending."
        EngineExitCorrelationState.NO_MATCH ->
            "Heartbeat gap detected, but no matching historical process-exit record was reported."
        EngineExitCorrelationState.MATCHED -> buildString {
            append("Reason: ").append(correlation.reason ?: "Unknown")
            correlation.description?.let { append(" — ").append(it) }
            correlation.gapMillis?.let { append("; heartbeat gap: ").append(ageLabel(it)) }
        }
    }

    internal fun applicationExitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Process exited itself"
        ApplicationExitInfo.REASON_FREEZER -> "App freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_OTHER -> "System or other reason"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Package updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "Signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped app"
        else -> "Reason $reason"
    }

    internal fun ageLabel(ageMillis: Long): String = when {
        ageMillis < 1_000L -> "less than a second"
        ageMillis < 60_000L -> "${ageMillis / 1_000L}s"
        else -> "${ageMillis / 60_000L}m"
    }

    /**
     * Reads why this app's currently-scheduled JobScheduler jobs (WorkManager runs its deferrable
     * workers through JobScheduler) are still pending. Returns a comma-separated set of plain-language
     * causes, or null when nothing meaningful is blocked or the API is unavailable. Android 14 (API
     * 34) added `getPendingJobReason`; the whole read is wrapped so it fails closed on any device
     * that reports differently rather than crashing the diagnostics screen.
     */
    private fun readPendingScheduledJobReasons(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
                ?: return null
            scheduler.allPendingJobs
                .map { scheduler.getPendingJobReason(it.id) }
                .filter(::isReportablePendingJobReason)
                .map(::pendingJobReasonLabel)
                .distinct()
                .sorted()
                .takeIf { it.isNotEmpty() }
                ?.joinToString()
        }.getOrNull()
    }

    /**
     * Drops non-actionable reason codes: a job that is currently running, has no constraint holding
     * it, or reports an undefined/optimization state is not something the user can act on. Pure so
     * the filter is unit-testable.
     */
    internal fun isReportablePendingJobReason(reason: Int): Boolean = when (reason) {
        android.app.job.JobScheduler.PENDING_JOB_REASON_UNDEFINED,
        android.app.job.JobScheduler.PENDING_JOB_REASON_EXECUTING,
        android.app.job.JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID,
        android.app.job.JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION,
        -> false
        else -> true
    }

    /**
     * Maps a `JobScheduler.PENDING_JOB_REASON_*` code to a plain-language cause. Constant values are
     * inlined at compile time, so this is a pure Int→String mapping that is unit-testable on the JVM
     * without a JobScheduler.
     */
    internal fun pendingJobReasonLabel(reason: Int): String = when (reason) {
        android.app.job.JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "App standby bucket"
        android.app.job.JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "Background restricted"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "Battery low"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "Not charging"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "No connectivity"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> "Content trigger"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "Device not idle"
        android.app.job.JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Storage low"
        android.app.job.JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "Device state (thermal/power)"
        android.app.job.JobScheduler.PENDING_JOB_REASON_QUOTA -> "Out of run quota"
        android.app.job.JobScheduler.PENDING_JOB_REASON_USER -> "User restriction"
        else -> "Constraint $reason"
    }

    internal fun foregroundServiceTypeLabel(types: Int): String {
        if (types == 0) return "None recorded"
        return buildList {
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0) add("special use")
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
        }.ifEmpty { listOf("unknown ($types)") }.joinToString()
    }

    internal fun workerStopReasonLabel(reason: Int): String = when (reason) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "Not stopped"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "Cancelled by app"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "Battery constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "Charging constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Connectivity constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "Device-idle constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Storage constraint"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "Device state"
        WorkInfo.STOP_REASON_TIMEOUT -> "Timed out"
        WorkInfo.STOP_REASON_UNKNOWN -> "Unknown"
        else -> "Reason $reason"
    }

    internal fun standbyBucketLabel(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Unavailable before Android 9"
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return "Unavailable"
        return when (manager.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
            else -> "Unknown"
        }
    }

    private fun standbyBucketThrottled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return false
        return isThrottledStandbyBucket(manager.appStandbyBucket)
    }

    /**
     * RARE and RESTRICTED buckets throttle the per-minute alarm and deferrable workers, degrading
     * trigger latency; ACTIVE/WORKING_SET/FREQUENT do not. Pure so bucket-to-warning mapping is
     * unit-testable without a UsageStatsManager.
     */
    internal fun isThrottledStandbyBucket(bucket: Int): Boolean =
        bucket == UsageStatsManager.STANDBY_BUCKET_RARE ||
            bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED

    internal const val MAX_HISTORICAL_EXITS = 20
    internal const val MAX_EXIT_DESCRIPTION_CHARS = 512
}
