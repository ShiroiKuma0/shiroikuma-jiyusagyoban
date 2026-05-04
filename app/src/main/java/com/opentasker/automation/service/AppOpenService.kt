package com.opentasker.automation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.opentasker.automation.core.AutomationEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background service that monitors app open/close events.
 * Uses polling with UsageStatsManager (API 21+) or AccessibilityService.
 */
@AndroidEntryPoint
class AppOpenService : Service() {
    @Inject
    lateinit var automationEngine: AutomationEngine
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val lastSeenApps = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("AppOpenService started")

        scope.launch {
            while (true) {
                try {
                    // Poll for app changes every 2 seconds
                    delay(2000)
                    checkAppChanges()
                } catch (e: Exception) {
                    log("Error checking app changes", e)
                }
            }
        }

        return START_STICKY
    }

    private suspend fun checkAppChanges() {
        // TODO: Implement using UsageStatsManager or ActivityManager
        // For now, this is a placeholder
        // Real implementation would:
        // 1. Query UsageStatsManager for recent apps
        // 2. Compare with lastSeenApps
        // 3. Dispatch AppEvent for opens/closes
        // 4. Update lastSeenApps

        // val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        // val now = System.currentTimeMillis()
        // val stats = usageStatsManager?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3000, now)
        // stats?.forEach { stat ->
        //     if (!lastSeenApps.contains(stat.packageName)) {
        //         val engine = AutomationEngine.getInstance(this)
        //         engine.onEvent(AutomationEvent.AppEvent(stat.packageName, true))
        //         lastSeenApps.add(stat.packageName)
        //     }
        // }
    }

    override fun onDestroy() {
        log("AppOpenService destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun log(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "AppOpenService"
    }
}
