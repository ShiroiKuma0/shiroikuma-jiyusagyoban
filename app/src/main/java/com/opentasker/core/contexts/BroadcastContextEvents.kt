package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.opentasker.core.actions.IntentReplyBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/**
 * Generic "Intent Received" trigger — the equivalent of Tasker's broadcast-receiver context
 * (code 599). A profile EVENT context with config `{ event: "broadcast", action: "<action>" }`
 * fires whenever that system/app broadcast arrives.
 *
 * The intent's top-level extras are published as **super-global** variables `INTENT_<KEY>`
 * (uppercased, non-alphanumerics → `_`), plus `%INTENT_ACTION`, so the triggered task can branch
 * on them — e.g. Poweramp's `com.maxmpz.audioplayer.STATUS_CHANGED` exposes `%INTENT_PAUSED`.
 *
 * Receivers are registered dynamically: [AutomationService] calls [setActions] with the set of
 * broadcast actions used by the currently-enabled profiles, so we only listen for what's needed.
 */
object BroadcastContextEvents {
    private val flow = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ContextEvent> = flow.asSharedFlow()

    private var receiver: BroadcastReceiver? = null
    private var registered: Set<String> = emptySet()

    /** Register a single receiver for exactly [actions] (replacing any previous registration). */
    @Synchronized
    fun setActions(context: Context, actions: Set<String>) {
        if (actions == registered) return
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        registered = emptySet()
        if (actions.isEmpty()) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let { handle(it) }
            }
        }
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        // EXPORTED: these are other apps' broadcasts (e.g. Poweramp), not our own.
        ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
        registered = actions
    }

    /** The action set currently registered, for the Context Inspector. */
    @Synchronized
    fun listeningActions(): Set<String> = registered

    @Synchronized
    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        registered = emptySet()
    }

    private fun handle(intent: Intent) {
        val action = intent.action ?: return
        val metadata = linkedMapOf("event" to "broadcast", "action" to action)
        // `%INTENT_*` are threaded PER-INVOCATION to the triggered enter task via ContextEvent.vars — NOT
        // persisted as super-globals. Poweramp dumps ~30 extras per TRACK_CHANGED; persisting them all
        // permanently polluted the global namespace, and the only readers are the broadcast enter tasks,
        // which already receive these locals.
        val vars = linkedMapOf("INTENT_ACTION" to action)

        fun publish(key: String, raw: Any?) {
            when (raw) {
                null -> {}
                // Flatten one level of nested Bundle (e.g. Poweramp's TRACK_CHANGED `track` → path/rating).
                is android.os.Bundle -> for (k in raw.keySet() ?: emptySet()) publish(k, runCatching { raw.get(k) }.getOrNull())
                else -> {
                    val value = raw.toString()
                    val name = "INTENT_" + key.uppercase(Locale.US).map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                    vars[name] = value
                    metadata[key] = value
                }
            }
        }
        runCatching { intent.extras }.getOrNull()?.let { extras ->
            for (key in extras.keySet() ?: emptySet()) publish(key, runCatching { extras.get(key) }.getOrNull())
        }
        // A broadcast echoing a pending request's correlation id (a sister app's progress report) is
        // that request's heartbeat — it keeps intent.send's watchdog from writing the app off. The
        // report's own values travel with it, so the watchdog can tell a heartbeat that is merely
        // repeating itself from one that shows the work moving.
        runCatching { intent.getStringExtra(IntentReplyBridge.EXTRA_REPLY_ID) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { id ->
                val report = metadata.entries
                    .filter { it.key != IntentReplyBridge.EXTRA_REPLY_ID }
                    .sortedBy { it.key }
                    .joinToString("") { "${it.key}=${it.value}" }
                IntentReplyBridge.touch(id, report)
            }
        flow.tryEmit(ContextEvent(type = "event", matched = true, metadata = metadata, vars = vars))
    }
}
