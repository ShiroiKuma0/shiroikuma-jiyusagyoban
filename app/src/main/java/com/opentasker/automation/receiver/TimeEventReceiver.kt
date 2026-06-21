package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineHeartbeat
import com.opentasker.core.scheduling.ExactAlarmSupport

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            TimeEventScheduler.ACTION_TIME_TICK,
            Intent.ACTION_TIME_TICK -> {
                try {
                    // This exact alarm fires through Doze. If EMUI reaped the process, resurrect the service;
                    // if the process lives but the engine's tick went stale (its coroutines died), re-arm it.
                    when {
                        !AutomationService.isRunning -> {
                            EngineHeartbeat.markResurrect()
                            AutomationService.start(context)
                        }
                        EngineHeartbeat.isStale() -> AutomationService.rearm(context)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error processing time event", e)
                } finally {
                    TimeEventScheduler(context).scheduleNextMinute()
                }
            }
            ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> {
                try {
                    android.util.Log.d(TAG, "Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error rescheduling time tick after exact alarm permission change", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "TimeEventReceiver"
    }
}
