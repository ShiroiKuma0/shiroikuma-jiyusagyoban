package com.opentasker.core.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Arms the app-owned minute pulse without touching credential-protected app state. */
class DirectBootTimeScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun scheduleNextMinute(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val manager = alarmManager ?: return false
        val pendingIntent = tickPendingIntent()
        val triggerAtMillis = nextMinuteBoundaryMillis(nowMillis)
        return runCatching {
            manager.cancel(pendingIntent)
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }.onFailure { error ->
            AppLogger.warn(TAG, "Could not arm the pre-unlock time trigger", error)
        }.isSuccess
    }

    fun cancel() {
        alarmManager?.cancel(tickPendingIntent())
    }

    private fun tickPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, DirectBootTimeReceiver::class.java)
            .setAction(ACTION_DIRECT_BOOT_TIME_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_DIRECT_BOOT_TIME_TICK = "com.opentasker.action.DIRECT_BOOT_TIME_TICK"
        private const val REQUEST_CODE = 13002
        private const val MINUTE_MILLIS = 60_000L
        private const val TAG = "DirectBootTimeScheduler"

        internal fun nextMinuteBoundaryMillis(nowMillis: Long): Long =
            ((nowMillis / MINUTE_MILLIS) + 1L) * MINUTE_MILLIS
    }
}

/** Receives only the explicit device-protected alarm and records a bounded pending pulse. */
class DirectBootTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DirectBootTimeScheduler.ACTION_DIRECT_BOOT_TIME_TICK) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val nowMillis = System.currentTimeMillis()
                if (!DirectBootTriggerStore.isUserUnlocked(context) &&
                    DirectBootTriggerStore.isEnabled(context)
                ) {
                    DirectBootTriggerStore.markTimeTickPending(context, nowMillis)
                    DirectBootTimeScheduler(context).scheduleNextMinute(nowMillis)
                } else {
                    // Once the first unlock has happened, the normal credential-protected alarm
                    // owns time scheduling. Never touch its ledger from this receiver.
                    DirectBootTimeScheduler(context).cancel()
                }
            } catch (error: Exception) {
                AppLogger.error(TAG, "Pre-unlock time trigger handling failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DirectBootTimeReceiver"
    }
}
