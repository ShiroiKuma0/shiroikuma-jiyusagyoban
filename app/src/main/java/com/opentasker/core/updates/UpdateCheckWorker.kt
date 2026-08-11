package com.opentasker.core.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.app.BuildConfig
import com.opentasker.core.actions.HttpRequestAction
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.TimeUnit

/** Build-gated availability for the public-release check. F-Droid supplies its own updates. */
object UpdateCheckAvailability {
    const val FDROID_DISTRIBUTION = "fdroid"

    fun isAvailable(): Boolean = BuildConfig.DISTRIBUTION != FDROID_DISTRIBUTION
}

/** Performs one bounded release-feed request; it never downloads or installs an artifact. */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!UpdateCheckAvailability.isAvailable()) return Result.success()
        val settings = UpdateCheckSettings(applicationContext)
        if (!settings.load().enabled) return Result.success()

        val variables = VariableStore()
        val result = HttpRequestAction().run(
            ActionContext(applicationContext, variables),
            UpdateCheckProtocol.requestArguments(),
        )
        if (result !is ActionResult.Success) {
            AppLogger.info(TAG, "Scheduled release check did not complete")
            return Result.success()
        }

        val payload = variables.get(UpdateCheckProtocol.RESPONSE_VARIABLE)
            ?: return Result.success()
        when (val parsed = UpdateCheckProtocol.parseLatestRelease(payload, BuildConfig.VERSION_NAME)) {
            UpdateCheckResult.NoUpdate,
            is UpdateCheckResult.Available,
            -> settings.record(parsed, System.currentTimeMillis())
            is UpdateCheckResult.Invalid -> {
                // Do not replace a previously valid result with untrusted/malformed response data.
                AppLogger.warn(TAG, "Scheduled release check returned an invalid response")
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val WORK_NAME = "optional_release_update_check"
        private const val INTERVAL_HOURS = 24L

        fun sync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val settings = UpdateCheckSettings(context).load()
            if (!UpdateCheckAvailability.isAvailable() || !settings.enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueIfEnabled(context: Context) = sync(context)
    }
}
