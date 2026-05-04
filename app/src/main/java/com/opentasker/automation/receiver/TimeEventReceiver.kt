package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.scheduling.ExactAlarmSupport

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : AutomationBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            TimeEventScheduler.ACTION_TIME_TICK,
            Intent.ACTION_TIME_TICK -> {
                try {
                    log("Time tick event")
                    automationEngine.onEvent(AutomationEvent.TimeEvent(System.currentTimeMillis()))
                } catch (e: Exception) {
                    log("Error processing time event", e)
                } finally {
                    TimeEventScheduler(context).scheduleNextMinute()
                }
            }
            ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> {
                try {
                    log("Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    log("Error rescheduling time tick after exact alarm permission change", e)
                }
            }
        }
    }
}
