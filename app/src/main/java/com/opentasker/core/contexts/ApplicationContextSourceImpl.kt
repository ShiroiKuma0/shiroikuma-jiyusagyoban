package com.opentasker.core.contexts

import android.content.Context
import android.app.usage.UsageStatsManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Real ApplicationContextSource using UsageStatsManager to track foreground app.
 *
 * Requires PACKAGE_USAGE_STATS permission. Polls every 500ms for foreground app changes.
 *
 * Config:
 *   - "apps": comma-separated package names (e.g., "com.android.chrome,com.spotify.music")
 */
class ApplicationContextSourceImpl : ContextSource {
    override val type = "app"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        var lastForeground = ""
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

        // Emit initial state
        trySend(ContextEvent(type, false, mapOf("foreground" to "")))

        val pollJob = launch {
            // Poll foreground app every 500ms
            while (isActive) {
                try {
                    val foreground = getCurrentForegroundApp(app, usm)
                    if (foreground != lastForeground) {
                        lastForeground = foreground
                        trySend(ContextEvent(type, true, mapOf("foreground" to foreground)))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Foreground app polling failed", e)
                }
                delay(500)
            }
        }

        awaitClose {
            pollJob.cancel()
        }
    }

    private fun getCurrentForegroundApp(app: Context, usm: UsageStatsManager?): String {
        if (usm == null) return ""
        try {
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 1000, now)
            return stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    companion object {
        private const val TAG = "ApplicationContextSource"
    }
}
