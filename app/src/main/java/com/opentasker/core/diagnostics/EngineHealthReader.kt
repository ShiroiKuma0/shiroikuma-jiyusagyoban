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
    val exactAlarmStatus: String,
    val lastMatcherError: String?,
    val lastMatcherErrorAtMillis: Long,
    val lastWorkerStopReason: String?,
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
            exactAlarmStatus = when (ExactAlarmSupport.schedulePrecision(context)) {
                AlarmSchedulePrecision.Exact -> "Exact allowed"
                AlarmSchedulePrecision.InexactFallback -> "Inexact Doze fallback"
            },
            lastMatcherError = persisted.lastMatcherError?.let(DiagnosticExport::redactSensitive),
            lastMatcherErrorAtMillis = persisted.lastMatcherErrorAtMillis,
            lastWorkerStopReason = workerStopReason?.let(::workerStopReasonLabel),
        )
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
}
