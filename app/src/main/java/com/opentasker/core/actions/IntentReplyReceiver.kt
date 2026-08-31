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
    /**
     * A target's answer: its status line, and every other string extra it sent.
     *
     * The extras used to be dropped on the floor — only [EXTRA_RESULT] survived. That was fine while
     * the only round-trip was a sister app reporting "OK:" or "ERROR:", and wrong the moment one
     * answered with actual VALUES: 天気 returns a temperature, a high, a low and a humidity as named
     * extras beside the status line, and every one of them would have been discarded silently.
     */
    data class Reply(val result: String, val extras: Map<String, String> = emptyMap())

    private val waiters = ConcurrentHashMap<String, (Reply) -> Unit>()

    /** Last sign of life per pending request, and last time what it reported actually CHANGED. */
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val lastChange = ConcurrentHashMap<String, Long>()
    private val lastReport = ConcurrentHashMap<String, String>()

    fun register(id: String, onResult: (Reply) -> Unit) {
        waiters[id] = onResult
        val now = android.os.SystemClock.elapsedRealtime()
        lastSeen[id] = now
        lastChange[id] = now
    }

    fun cancel(id: String) = forget(id).also { waiters.remove(id) }
    fun deliver(id: String, reply: Reply) { forget(id); waiters.remove(id)?.invoke(reply) }

    /** The keys that are protocol rather than payload, and so are never surfaced as variables. */
    val PLUMBING = setOf(EXTRA_REPLY_ACTION, EXTRA_REPLY_PACKAGE, EXTRA_REPLY_ID, EXTRA_RESULT)

    private fun forget(id: String) {
        lastSeen.remove(id); lastChange.remove(id); lastReport.remove(id)
    }

    /**
     * A broadcast from the target carrying its `reply_id` — in practice a progress report.
     *
     * Two different things are tracked, because they answer two different questions. **Alive** is
     * "anything arrived": a killed process sends nothing, so silence means death. **Progressing** is
     * "what arrived was different from last time" ([report] is the report's fingerprint): an export
     * that hangs while its heartbeat coroutine keeps re-sending the same line is alive and going
     * nowhere, and it would otherwise hold the caller for the entire timeout. Only a CHANGED report
     * counts as progress.
     */
    fun touch(id: String, report: String = "") {
        if (!waiters.containsKey(id)) return
        val now = android.os.SystemClock.elapsedRealtime()
        lastSeen[id] = now
        if (report.isEmpty() || lastReport.put(id, report) != report) lastChange[id] = now
    }

    /** Milliseconds since the last sign of life, or null if nothing is pending under [id]. */
    fun silentFor(id: String): Long? =
        lastSeen[id]?.let { android.os.SystemClock.elapsedRealtime() - it }

    /** Milliseconds since the target last reported something NEW — the real liveness question. */
    fun stalledFor(id: String): Long? =
        lastChange[id]?.let { android.os.SystemClock.elapsedRealtime() - it }

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
        // Every string extra the target sent, minus the plumbing. Only strings: this channel exists
        // because EMUI will not carry anything richer between two third-party apps, and a value that
        // arrived as some other type would be a sign the contract was not followed, not a bonus.
        val extras = buildMap {
            intent.extras?.keySet()?.forEach { key ->
                if (key in IntentReplyBridge.PLUMBING) return@forEach
                (intent.getStringExtra(key))?.let { put(key, it) }
            }
        }
        IntentReplyBridge.deliver(
            id,
            IntentReplyBridge.Reply(
                intent.getStringExtra(IntentReplyBridge.EXTRA_RESULT).orEmpty(),
                extras,
            ),
        )
    }
}
