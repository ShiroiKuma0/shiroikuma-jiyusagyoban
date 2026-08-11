package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.core.logging.AppLogger

/** Receives the legacy explicit, token-authenticated delivery envelope. */
class PushEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = PushContextEvents.publishFromIntent(
            intent = intent,
            expectedToken = PushTriggerTokenStore(context).token(),
        )
        AppLogger.debug(TAG, "Push event accepted=$accepted action=${intent.action}")
    }

    private companion object {
        const val TAG = "PushEventReceiver"
    }
}
