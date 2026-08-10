package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.scheduling.ExpectedTriggerLedger
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.ExactAlarmSupport

internal enum class TimeEventAction {
    TIME_TICK,
    EXACT_ALARM_PERMISSION_CHANGED,
    IGNORE,
}

internal fun classifyTimeEventAction(action: String?): TimeEventAction = when (action) {
    TimeEventScheduler.ACTION_TIME_TICK,
    Intent.ACTION_TIME_TICK -> TimeEventAction.TIME_TICK
    ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED
    else -> TimeEventAction.IGNORE
}

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (classifyTimeEventAction(intent.action)) {
            TimeEventAction.TIME_TICK -> {
                ExpectedTriggerLedger(context).markDelivered(System.currentTimeMillis())
                val scheduler = TimeEventScheduler(context)
                runCatching { scheduler.scheduleNextMinute() }
                    .onFailure { AppLogger.error(TAG, "Could not re-arm the next time tick", it) }
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AutomationService::class.java)
                            .setAction(AutomationService.ACTION_TIME_TICK_TRIGGER),
                    )
                }.onSuccess {
                    AppLogger.debug(TAG, "Delivered alarm-backed time tick to the engine")
                }.onFailure { error ->
                    AppLogger.error(TAG, "Could not deliver alarm-backed time tick", error)
                    runCatching { scheduler.scheduleRecovery() }
                        .onFailure { AppLogger.error(TAG, "Could not schedule time-tick recovery", it) }
                }
            }
            TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED -> {
                try {
                    AppLogger.debug(TAG, "Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Error rescheduling time tick after exact alarm permission change", e)
                }
            }
            TimeEventAction.IGNORE -> Unit
        }
    }

    private companion object {
        const val TAG = "TimeEventReceiver"
    }
}
