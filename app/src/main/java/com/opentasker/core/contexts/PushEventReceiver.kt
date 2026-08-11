package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.core.logging.AppLogger

/**
 * Receives the explicit, token-authenticated delivery envelope.
 *
 * The parser also accepts ntfy's documented unprefixed `topic`, `id`, `title`, and `message`
 * names. That lets an ntfy notification `broadcast` action target ACTION_PUSH_EVENT directly;
 * the per-install OpenTasker token remains mandatory. The receiver intentionally does not expose
 * the unauthenticated ntfy MESSAGE_RECEIVED action as a manifest entry point.
 */
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
