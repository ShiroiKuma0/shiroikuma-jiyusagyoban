package com.opentasker.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opentasker.core.logging.AppLogger

/** Restarts [AutomationService] after device boot and requests a boot event pulse. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
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
