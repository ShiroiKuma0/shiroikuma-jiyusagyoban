package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * Reply channel for [SendIntentAction]'s "receiver" mode — **binder-free**. We hand the target app
 * only plain string extras (a reply action, our package, and a random correlation id); when it's done
 * it fires a normal broadcast back to this exported receiver carrying that id + the result line.
 *
 * Why no ResultReceiver / PendingIntent: this EMUI does not reliably carry a live Binder into another
 * app's manifest receiver (verified 2026-07-23 — a ResultReceiver extra got the whole broadcast
 * dropped; a PendingIntent extra was delivered but the callback never fired). A plain reply broadcast
 * with string extras survives. Spoofing is bounded by the random per-call [EXTRA_REPLY_ID] (a UUID the
 * target must echo), which no other app can guess.
 */
object IntentReplyBridge {
    private val waiters = ConcurrentHashMap<String, (String) -> Unit>()

    fun register(id: String, onResult: (String) -> Unit) { waiters[id] = onResult }
    fun cancel(id: String) { waiters.remove(id) }
    fun deliver(id: String, result: String) { waiters.remove(id)?.invoke(result) }

    const val ACTION_INTENT_REPLY = "shiroikuma.jiyusagyoban.action.INTENT_REPLY"
    const val EXTRA_REPLY_ACTION = "reply_action"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_REPLY_ID = "reply_id"
    const val EXTRA_RESULT = "result"
}

class IntentReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IntentReplyBridge.ACTION_INTENT_REPLY) return
        val id = intent.getStringExtra(IntentReplyBridge.EXTRA_REPLY_ID) ?: return
        IntentReplyBridge.deliver(id, intent.getStringExtra(IntentReplyBridge.EXTRA_RESULT).orEmpty())
    }
}
