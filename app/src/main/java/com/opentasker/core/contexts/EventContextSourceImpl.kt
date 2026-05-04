package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Context.RECEIVER_EXPORTED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real EventContextSource using BroadcastReceivers and system intents.
 *
 * Supported events:
 *   - "sms_received": incoming SMS
 *   - "notification": notification posted (requires NotificationListenerService)
 *   - "boot_completed": device boot
 *   - "intent": arbitrary intent action (configurable)
 */
class EventContextSourceImpl : ContextSource {
    override val type = "event"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val event = when (intent.action) {
                    "android.provider.Telephony.SMS_RECEIVED" -> "sms_received"
                    Intent.ACTION_BOOT_COMPLETED -> "boot_completed"
                    else -> intent.action?.lowercase() ?: return
                }
                trySend(ContextEvent(type, true, mapOf("event" to event)))
            }
        }

        val filter = IntentFilter().apply {
            addAction("android.provider.Telephony.SMS_RECEIVED")
            addAction(Intent.ACTION_BOOT_COMPLETED)
        }

        app.registerReceiver(receiver, filter, RECEIVER_EXPORTED)

        awaitClose { app.unregisterReceiver(receiver) }
    }
}
