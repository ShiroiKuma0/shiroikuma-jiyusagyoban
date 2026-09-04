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
    /**
     * Guarded because this is exported and anyone can send to it.
     *
     * Reading an extra unparcels the bundle, and a sender can put a value whose class does not
     * exist in this process. Below API 33 that throws on the first read, and on 33+ it throws when
     * a poisoned key is the one being read. Uncaught in a receiver that would kill the process
     * hosting the automation engine, which START_STICKY then restarts for the sender to do again.
     * The other exported receivers already wrap for this reason; these two did not.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = runCatching {
            PushContextEvents.publishFromIntent(
                intent = intent,
                expectedToken = PushTriggerTokenStore(context).token(),
            )
        }.getOrElse { error ->
            AppLogger.warn(TAG, "Discarded a push delivery whose extras could not be read", error)
            false
        }
        AppLogger.debug(TAG, "Push event accepted=$accepted action=${intent.action}")
    }

    private companion object {
        const val TAG = "PushEventReceiver"
    }
}
