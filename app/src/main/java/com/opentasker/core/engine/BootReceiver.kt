package com.opentasker.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import androidx.core.content.ContextCompat
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles both the direct-boot pulse arm and the normal post-unlock engine startup. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> armDirectBootTrigger(context)
            Intent.ACTION_USER_UNLOCKED -> {
                initializeAfterUnlock(context)
                DirectBootTimeScheduler(context).cancel()
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                DirectBootTimeScheduler(context).cancel()
                if (initializeAfterUnlock(context)) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, AutomationService::class.java)
                                .setAction(AutomationService.ACTION_BOOT_COMPLETED_TRIGGER),
                        )
                    }.onFailure { error ->
                        AppLogger.error("OpenTasker", "Failed to start automation service after boot", error)
                    }
                }
            }
        }
    }

    private fun armDirectBootTrigger(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!DirectBootTriggerStore.isUserUnlocked(context) &&
                    DirectBootTriggerStore.isEnabled(context)
                ) {
                    DirectBootTimeScheduler(context).scheduleNextMinute()
                }
            } catch (error: Exception) {
                AppLogger.error("OpenTasker", "Failed to arm the pre-unlock time trigger", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun initializeAfterUnlock(context: Context): Boolean = runCatching {
        (context.applicationContext as? OpenTaskerApp_NoHilt)?.initializeAfterUnlock()
            ?: error("OpenTasker application is unavailable")
    }.onFailure { error ->
        AppLogger.error("OpenTasker", "Credential-protected startup failed", error)
    }.isSuccess
}
