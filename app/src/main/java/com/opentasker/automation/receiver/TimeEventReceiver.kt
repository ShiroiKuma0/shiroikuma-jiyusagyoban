package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import com.opentasker.automation.model.AutomationEvent

/**
 * Time tick event receiver.
 * Fires every minute and dispatches TimeEvent.
 */
class TimeEventReceiver : AutomationBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_TIME_TICK) {
            try {
                log("Time tick event")
                automationEngine.onEvent(AutomationEvent.TimeEvent(System.currentTimeMillis()))
            } catch (e: Exception) {
                log("Error processing time event", e)
            }
        }
    }
}
