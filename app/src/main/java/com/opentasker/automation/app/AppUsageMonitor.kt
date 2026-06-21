package com.opentasker.automation.app

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.opentasker.core.contexts.AppForegroundChangedContextEvents
import com.opentasker.core.contexts.ApplicationContextEvents
import com.opentasker.core.permissions.UsageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppUsageMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var monitorJob: Job? = null
    private var lastForegroundPackage: String? = null
    private var warnedMissingAccess = false

    fun start(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!UsageAccess.hasUsageStatsAccess(appContext)) {
                    if (!warnedMissingAccess) {
                        Log.w(TAG, "Usage access is not granted; app-open triggers are paused")
                        warnedMissingAccess = true
                    }
                    delay(MISSING_ACCESS_RETRY_MS)
                    continue
                }

                warnedMissingAccess = false
                pollForegroundPackage()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun pollForegroundPackage() {
        val now = System.currentTimeMillis()
        val currentPackage = readForegroundPackage(now) ?: return
        val previousPackage = lastForegroundPackage

        if (currentPackage == previousPackage) return

        ApplicationContextEvents.publishForeground(currentPackage)
        AppForegroundChangedContextEvents.publish(currentPackage)
        lastForegroundPackage = currentPackage
        Log.d(TAG, "Foreground app changed: $previousPackage -> $currentPackage")
    }

    private fun readForegroundPackage(nowMillis: Long): String? {
        return try {
            val usageEvents = usageStatsManager.queryEvents(nowMillis - LOOKBACK_WINDOW_MS, nowMillis)
            val event = UsageEvents.Event()
            val foregroundEvents = mutableListOf<ForegroundUsageEvent>()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (isForegroundEvent(event.eventType)) {
                    foregroundEvents += ForegroundUsageEvent(
                        packageName = event.packageName.orEmpty(),
                        timestamp = event.timeStamp,
                    )
                }
            }

            selectLatestForegroundPackage(foregroundEvents)
        } catch (ex: SecurityException) {
            Log.w(TAG, "UsageStatsManager query denied; app-open triggers are paused", ex)
            null
        } catch (ex: RuntimeException) {
            Log.e(TAG, "UsageStatsManager query failed", ex)
            null
        }
    }

    companion object {
        private const val TAG = "AppUsageMonitor"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MISSING_ACCESS_RETRY_MS = 5_000L
        private const val LOOKBACK_WINDOW_MS = 10_000L

        @Suppress("DEPRECATION")
        @SuppressLint("InlinedApi")
        private fun isForegroundEvent(eventType: Int): Boolean =
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_RESUMED)

        internal fun selectLatestForegroundPackage(events: List<ForegroundUsageEvent>): String? =
            events
                .asSequence()
                .filter { it.packageName.isNotBlank() }
                .maxByOrNull { it.timestamp }
                ?.packageName
    }
}

internal data class ForegroundUsageEvent(
    val packageName: String,
    val timestamp: Long,
)
