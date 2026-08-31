package com.opentasker.automation.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.opentasker.automation.receiver.TimeEventReceiver
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scheduling.ExpectedTriggerKind
import com.opentasker.core.scheduling.ExpectedTriggerLedger

class TimeEventScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val expectedTriggers = ExpectedTriggerLedger(appContext)

    fun scheduleNextMinute(nowMillis: Long = System.currentTimeMillis()) {
        scheduleAt(nextMinuteBoundaryMillis(nowMillis), ExpectedTriggerKind.MINUTE_TICK, nowMillis)
    }

    fun scheduleRecovery(nowMillis: Long = System.currentTimeMillis()) {
        scheduleAt(recoveryTriggerAtMillis(nowMillis), ExpectedTriggerKind.RECOVERY, nowMillis)
    }

    private fun scheduleAt(
        triggerAtMillis: Long,
        kind: ExpectedTriggerKind,
        scheduledFromMillis: Long,
    ) {
        val pendingIntent = tickPendingIntent()

        alarmManager.cancel(pendingIntent)
        when (scheduleMode(ExactAlarmSupport.schedulePrecision(appContext))) {
            AlarmSchedulePrecision.Exact -> {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    AppLogger.debug(TAG, "Scheduled exact time tick for $triggerAtMillis")
                } catch (error: SecurityException) {
                    scheduleInexactWhileIdle(triggerAtMillis, pendingIntent)
                    AppLogger.warn(TAG, "Exact-alarm access changed while scheduling; used Doze-capable fallback")
                }
            }
            AlarmSchedulePrecision.InexactFallback -> {
                scheduleInexactWhileIdle(triggerAtMillis, pendingIntent)
                AppLogger.warn(TAG, "Exact alarms unavailable; scheduled Doze-capable inexact time tick for $triggerAtMillis")
            }
        }
        expectedTriggers.recordExpected(kind, triggerAtMillis, scheduledFromMillis)
    }

    private fun scheduleInexactWhileIdle(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
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
        internal const val RECOVERY_DELAY_MS = 5_000L
        private const val TAG = "TimeEventScheduler"

        internal fun nextMinuteBoundaryMillis(nowMillis: Long): Long =
            ((nowMillis / MINUTE_MS) + 1L) * MINUTE_MS

        internal fun recoveryTriggerAtMillis(nowMillis: Long): Long = nowMillis + RECOVERY_DELAY_MS

        internal fun scheduleMode(precision: AlarmSchedulePrecision): AlarmSchedulePrecision = precision
    }
}
