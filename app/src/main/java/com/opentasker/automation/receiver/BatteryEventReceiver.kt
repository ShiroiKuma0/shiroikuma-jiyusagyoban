package com.opentasker.automation.receiver

import android.content.Context
import android.content.Intent
import android.intent.action.Intent
import android.os.BatteryManager
import com.opentasker.automation.model.AutomationEvent

/**
 * Battery level change event receiver.
 * Dispatches BatteryEvent when battery state changes.
 */
class BatteryEventReceiver : AutomationBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
            try {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                val statusString = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                }

                log("Battery event: level=$level%, status=$statusString")

                // TODO: Post event to automation engine
                // val engine = AutomationEngine.getInstance(context)
                // engine.onEvent(AutomationEvent.BatteryEvent(level, statusString))
            } catch (e: Exception) {
                log("Error processing battery event", e)
            }
        }
    }
}
