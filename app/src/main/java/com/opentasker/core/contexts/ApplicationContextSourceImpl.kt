package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Application context source backed by the foreground-service app usage monitor.
 *
 * Requires PACKAGE_USAGE_STATS permission. [com.opentasker.automation.app.AppUsageMonitor]
 * publishes foreground app changes while the automation engine is running.
 *
 * Config:
 *   - "apps": comma-separated package names (e.g., "com.android.chrome,com.spotify.music")
 */
class ApplicationContextSourceImpl : ContextSource {
    override val type = "app"

    override fun events(app: Context): Flow<ContextEvent> = ApplicationContextEvents.events
}
