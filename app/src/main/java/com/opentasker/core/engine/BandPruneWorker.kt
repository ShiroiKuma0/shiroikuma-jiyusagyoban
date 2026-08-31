package com.opentasker.core.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.band.BandRetentionSettings
import com.opentasker.core.band.cutoffLocalDate
import com.opentasker.core.band.cutoffLocalTs
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Trims the band's working set. A sibling of [RunLogPruneWorker].
 *
 * Safe to prune at all only because the JSONL archive keeps everything: the database is what the app
 * queries, the archive is the record. `band_syncs` is untouched here on purpose — it is the census
 * series that measures the band's ring-buffer depth, and it is a few rows a day.
 */
class BandPruneWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = OpenTaskerApp_NoHilt.db
        val policy = BandRetentionSettings(applicationContext).load()
        return try {
            var deleted = db.bandSampleDao().deleteOlderThan(policy.cutoffLocalTs())
            deleted += db.bandSleepDao().deleteOlderThan(policy.cutoffLocalTs())
            deleted += db.bandDailyDao().deleteOlderThan(policy.cutoffLocalDate())

            // A row budget on top of the age limit, in case a very dense stream outgrows it.
            val remaining = db.bandSampleDao().count()
            if (remaining > policy.maxSamples) {
                deleted += db.bandSampleDao().deleteOldest(remaining - policy.maxSamples)
            }
            if (deleted > 0) AppLogger.info(TAG, "Periodic prune removed $deleted band rows")
            Result.success()
        } catch (e: Exception) {
            AppLogger.error(TAG, "Periodic band prune failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BandPruneWorker"
        private const val WORK_NAME = "band_prune"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<BandPruneWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
