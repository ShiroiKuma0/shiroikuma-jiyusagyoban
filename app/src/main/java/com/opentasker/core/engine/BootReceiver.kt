package com.opentasker.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.BootStartSettings

/** Restarts [AutomationService] after device boot and requests a boot event pulse. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // "Start engine on boot" (on by default — the long-standing behaviour). When it is off, a reboot
        // leaves the app down; when it is on, boot also lifts an "Exit app fully", since coming back up
        // with the device is the whole point of the setting.
        if (!BootStartSettings.isEnabled(context)) {
            AppLogger.info("OpenTasker", "Boot ignored — “Start engine on boot” is off")
            return
        }
        EngineShutdown.clear(context)
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
