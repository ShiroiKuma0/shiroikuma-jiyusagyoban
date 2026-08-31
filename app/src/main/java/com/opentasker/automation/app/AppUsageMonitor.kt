package com.opentasker.automation.app

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.opentasker.core.contexts.AppForegroundChangedContextEvents
import com.opentasker.core.contexts.ApplicationContextEvents
import com.opentasker.core.logging.AppLogger
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
    private var lastForegroundIdentity: ForegroundIdentity? = null
    private var warnedMissingAccess = false

    fun start(scope: CoroutineScope): Boolean {
        if (monitorJob?.isActive == true) return true
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (appUsagePollAction(UsageAccess.hasUsageStatsAccess(appContext)) == AppUsagePollAction.PAUSE_FOR_MISSING_ACCESS) {
                    if (!warnedMissingAccess) {
                        AppLogger.warn(TAG, "Usage access is not granted; app-open triggers are paused")
                        warnedMissingAccess = true
                    }
                    delay(MISSING_ACCESS_RETRY_MS)
                    continue
                }

                warnedMissingAccess = false
                pollForegroundComponent()
                delay(POLL_INTERVAL_MS)
            }
        }
        return true
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun pollForegroundComponent() {
        val now = System.currentTimeMillis()
        val current = readForegroundEvent(now) ?: return
        val currentPackage = current.packageName.trim()
        val currentComponent = current.className.trim().ifBlank { null }
        val identity = ForegroundIdentity(currentPackage, currentComponent)
        val previous = lastForegroundIdentity

        if (identity == previous) return

        ApplicationContextEvents.publishForeground(currentPackage, currentComponent)
        // Fork: the 回転 port's per-app rotation rules ride on app_foreground events, which are
        // package-level — upstream's component precision above does not replace them.
        AppForegroundChangedContextEvents.publish(currentPackage)
        lastForegroundIdentity = identity
        AppLogger.debug(TAG, "Foreground app changed: ${previous?.packageName} -> $currentPackage/$currentComponent")
    }

    private fun readForegroundEvent(nowMillis: Long): ForegroundUsageEvent? {
        return try {
            val usageEvents = usageStatsManager.queryEvents(nowMillis - LOOKBACK_WINDOW_MS, nowMillis)
            val event = UsageEvents.Event()
            val foregroundEvents = mutableListOf<ForegroundUsageEvent>()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (isForegroundEvent(event.eventType)) {
                    foregroundEvents += ForegroundUsageEvent(
                        packageName = event.packageName.orEmpty(),
                        className = event.className.orEmpty(),
                        timestamp = event.timeStamp,
                    )
                }
            }

            selectLatestForegroundEvent(foregroundEvents)
        } catch (ex: SecurityException) {
            AppLogger.warn(TAG, "UsageStatsManager query denied; app-open triggers are paused", ex)
            null
        } catch (ex: RuntimeException) {
            AppLogger.error(TAG, "UsageStatsManager query failed", ex)
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
            selectLatestForegroundEvent(events)?.packageName

        internal fun selectLatestForegroundEvent(events: List<ForegroundUsageEvent>): ForegroundUsageEvent? =
            events
                .asSequence()
                .filter { it.packageName.isNotBlank() }
                .maxByOrNull { it.timestamp }
    }
}

internal data class ForegroundUsageEvent(
    val packageName: String,
    val className: String = "",
    val timestamp: Long,
)

private data class ForegroundIdentity(
    val packageName: String,
    val component: String?,
)
