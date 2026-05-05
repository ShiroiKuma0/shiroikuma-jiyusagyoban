package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.opentasker.core.plugins.locale.LocalePluginRequestQueryEvents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge

/**
 * Real EventContextSource using BroadcastReceivers and system intents.
 *
 * Supported events:
 *   - "sms_received": incoming SMS
 *   - "notification": notification posted (requires NotificationListenerService)
 *   - "calendar": local CalendarProvider event windows (requires READ_CALENDAR)
 *   - "sun_tick": local minute tick used by sunrise/sunset event filters
 *   - "nfc": NFC tag scan
 *   - "locale_request_query": Locale condition plugin requested a host query
 *   - "boot_completed": device boot
 *   - "intent": arbitrary intent action (configurable)
 */
class EventContextSourceImpl : ContextSource {
    override val type = "event"

    override fun events(app: Context): Flow<ContextEvent> = merge(
        systemBroadcastEvents(app),
        NotificationContextEvents.events,
        NfcContextEvents.events,
        CalendarSunContextEvents.events(app),
        LocalePluginRequestQueryEvents.events(app),
    )

    private fun systemBroadcastEvents(app: Context): Flow<ContextEvent> = callbackFlow {
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

        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        awaitClose {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
