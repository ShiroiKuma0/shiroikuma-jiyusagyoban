package com.opentasker.core.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Takes a local configuration snapshot on a schedule when the user has opted in.
 *
 * A snapshot is an ordinary managed backup, so it inherits the WAL checkpoint, schema validation,
 * and atomic-publication guarantees already in [DatabaseBackupManager]. Nothing here restores
 * anything: a snapshot is only ever applied through the existing inspect-then-stage review.
 */
class ConfigurationSnapshotWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = ConfigurationSnapshotSettings(applicationContext)
        val policy = settings.load()
        if (!policy.enabled) return Result.success()

        val manager = DatabaseBackupManager(applicationContext, OpenTaskerApp_NoHilt.db)
        val now = System.currentTimeMillis()
        return manager.backup().fold(
            onSuccess = {
                val removed = manager.pruneSnapshots(policy, now)
                settings.recordSuccess(now)
                AppLogger.info(TAG, "Configuration snapshot created; pruned $removed expired snapshot(s)")
                Result.success()
            },
            onFailure = { error ->
                settings.recordFailure(now, error.message)
                AppLogger.error(TAG, "Configuration snapshot failed", error)
                // Retrying keeps a transient failure (a busy WAL checkpoint) from silently
                // skipping a whole interval.
                Result.retry()
            },
        )
    }

    companion object {
        private const val TAG = "ConfigurationSnapshotWorker"
        internal const val WORK_NAME = "configuration_snapshot"
        private const val INTERVAL_HOURS = 12L

        /** Schedules or cancels the periodic snapshot to match [policy]. */
        fun sync(context: Context, policy: ConfigurationSnapshotPolicy) {
            val workManager = WorkManager.getInstance(context)
            if (!policy.enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ConfigurationSnapshotWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueIfEnabled(context: Context) {
            sync(context, ConfigurationSnapshotSettings(context).load())
        }
    }
}
