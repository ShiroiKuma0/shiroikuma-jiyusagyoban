package com.opentasker.core.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.TimeUnit

/** Periodic backstop for a killed/timed-out engine and a dropped self-rescheduling time alarm. */
class EngineWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val scheduler = TimeEventScheduler(applicationContext)
        val heartbeat = EngineHeartbeatStore(applicationContext).read()
        return runCatching {
            if (heartbeat.needsRecovery(now)) {
                scheduler.scheduleRecovery(now)
                AppLogger.warn(TAG, "Engine heartbeat stale; scheduled an alarm-backed restart")
            } else {
                scheduler.scheduleNextMinute(now)
                AppLogger.debug(TAG, "Engine heartbeat healthy; verified time alarm")
            }
            Result.success()
        }.getOrElse { error ->
            AppLogger.error(TAG, "Engine watchdog could not re-arm time delivery", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EngineWatchdogWorker"
        private const val WORK_NAME = "engine_watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<EngineWatchdogWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
