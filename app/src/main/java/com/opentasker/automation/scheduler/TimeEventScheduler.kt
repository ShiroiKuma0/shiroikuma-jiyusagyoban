package com.opentasker.automation.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opentasker.automation.receiver.TimeEventReceiver
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport

class TimeEventScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun scheduleNextMinute(nowMillis: Long = System.currentTimeMillis()) {
        val triggerAtMillis = nextMinuteBoundaryMillis(nowMillis)
        val pendingIntent = tickPendingIntent()

        alarmManager.cancel(pendingIntent)
        when (ExactAlarmSupport.schedulePrecision(appContext)) {
            AlarmSchedulePrecision.Exact -> {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d(TAG, "Scheduled exact time tick for $triggerAtMillis")
            }
            AlarmSchedulePrecision.InexactFallback -> {
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    INEXACT_WINDOW_MS,
                    pendingIntent,
                )
                Log.w(TAG, "Exact alarms unavailable; scheduled inexact time tick for $triggerAtMillis")
            }
        }
    }

    fun cancel() {
        alarmManager.cancel(tickPendingIntent())
    }

    private fun tickPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE_TIME_TICK,
            Intent(appContext, TimeEventReceiver::class.java).setAction(ACTION_TIME_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_TIME_TICK = "com.opentasker.automation.TIME_TICK"
        private const val REQUEST_CODE_TIME_TICK = 13001
        private const val MINUTE_MS = 60_000L
        private const val INEXACT_WINDOW_MS = 10 * MINUTE_MS
        private const val TAG = "TimeEventScheduler"

        internal fun nextMinuteBoundaryMillis(nowMillis: Long): Long =
            ((nowMillis / MINUTE_MS) + 1L) * MINUTE_MS
    }
}
