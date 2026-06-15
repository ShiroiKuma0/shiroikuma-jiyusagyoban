package com.opentasker.core.engine

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit

class RunLogPruneWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = OpenTaskerApp_NoHilt.db
        val policy = RunLogRetentionSettings(applicationContext).load()
        val now = System.currentTimeMillis()
        return try {
            val deleted = db.runLogDao().pruneRetention(
                maxEntries = policy.maxEntries,
                minimumTimestamp = policy.minimumTimestamp(now),
            )
            if (deleted > 0) {
                Log.i(TAG, "Periodic prune removed $deleted old run log entries")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Periodic run-log prune failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RunLogPruneWorker"
        private const val WORK_NAME = "run_log_prune"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<RunLogPruneWorker>(
                6, TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
