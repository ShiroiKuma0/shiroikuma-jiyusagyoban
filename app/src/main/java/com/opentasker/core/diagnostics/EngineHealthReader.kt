package com.opentasker.core.diagnostics

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opentasker.core.engine.EngineHeartbeatStore
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.needsRecovery
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
    /**
     * Plain-language reasons this app's scheduled jobs (WorkManager watchdog/prune) are still
     * pending — e.g. "App standby, Connectivity". Null when nothing is blocked or the signal is
     * unavailable (below Android 14). Answers the "why hasn't my scheduled automation fired" case.
     */
    val pendingScheduledJobReasons: String? = null,
)

object EngineHealthReader {
    suspend fun read(context: Context, nowMillis: Long = System.currentTimeMillis()): EngineHealthStatus {
        val persisted = EngineHeartbeatStore(context).readPersistedHealth()
        val workerStopReason = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(EngineWatchdogWorker.WORK_NAME)
                .first()
                .firstOrNull()
                ?.stopReason
        }.getOrNull()
        return EngineHealthStatus(
            serviceRunning = !persisted.heartbeat.needsRecovery(nowMillis),
            lastHeartbeatAtMillis = persisted.heartbeat.lastAliveAtMillis,
            activeForegroundServiceTypes = foregroundServiceTypeLabel(persisted.heartbeat.foregroundServiceTypes),
            standbyBucket = standbyBucketLabel(context),
            standbyThrottled = standbyBucketThrottled(context),
            advancedProtectionEnabled = AdvancedProtectionReader.isEnabled(context),
            exactAlarmStatus = when (ExactAlarmSupport.schedulePrecision(context)) {
                AlarmSchedulePrecision.Exact -> "Exact allowed"
                AlarmSchedulePrecision.InexactFallback -> "Inexact Doze fallback"
            },
            lastMatcherError = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive),
            lastMatcherErrorAtMillis = persisted.lastMatcherErrorAtMillis,
            lastWorkerStopReason = workerStopReason?.let(::workerStopReasonLabel),
            pendingScheduledJobReasons = readPendingScheduledJobReasons(context),
        )
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

    private fun standbyBucketLabel(context: Context): String {
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
}
