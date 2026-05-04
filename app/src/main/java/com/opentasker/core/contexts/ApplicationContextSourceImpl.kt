package com.opentasker.core.contexts

import android.content.Context
import android.app.usage.UsageStatsManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay

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
        val usm = if (Build.VERSION.SDK_INT >= 30) {
            app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } else {
            null
        }

        // Emit initial state
        trySend(ContextEvent(type, false, mapOf("foreground" to "")))

        // Poll foreground app every 500ms
        while (!isClosedForSend) {
            try {
                val foreground = getCurrentForegroundApp(app, usm)
                if (foreground != lastForeground) {
                    lastForeground = foreground
                    trySend(ContextEvent(type, true, mapOf("foreground" to foreground)))
                }
            } catch (e: Exception) {
                // silently continue
            }
            delay(500)
        }

        awaitClose()
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
}
