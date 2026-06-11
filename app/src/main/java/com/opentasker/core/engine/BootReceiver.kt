package com.opentasker.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

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
                Log.e("OpenTasker", "Failed to start automation service after boot", error)
            }
        }
    }
}
