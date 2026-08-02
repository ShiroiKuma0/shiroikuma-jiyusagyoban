package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.progress.ProgressPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Generic "Send Intent" action — fire an arbitrary Android intent with a custom
 * action, explicit component, data URI, MIME type, and up to six string extras,
 * dispatched as an activity, (foreground) service, or broadcast.
 *
 * This is the fork's reason for existing: it lets an OpenTasker task drive any
 * app's intents — first of all the token-gated automation intents of the sister
 * app 白い熊 GNU Jami (shiroikuma.jami): SEND_MESSAGE / PLACE_CALL /
 * PLACE_VIDEO_CALL / OPEN_CONVERSATION.
 *
 * Args:
 *   - "action":  intent action string (e.g. "shiroikuma.jami.action.SEND_MESSAGE",
 *                "android.intent.action.VIEW"). Optional if a component is given.
 *   - "package": target package (e.g. "shiroikuma.jami"). Optional.
 *   - "class":   explicit, fully-qualified component class
 *                (e.g. "cx.ring.automation.AutomationActivity"). Requires "package".
 *   - "data":    optional data URI (e.g. a "jami-cmd://…" deep link).
 *   - "mime":    optional MIME type.
 *   - "target":  "activity" (default) / "foreground-service" / "service" / "broadcast".
 *   - "flags":   optional extra intent flags, decimal or 0x-hex, OR'd into the intent.
 *   - "extra1_key"/"extra1_value" … "extra6_key"/"extra6_value": string extras.
 *   - "result_var": (broadcast only) send as an ORDERED broadcast and store the receiver's
 *                 result data into this variable — the query channel for sister-app round-trips
 *                 (e.g. GET_PROTECTED_CONTACTS returns the app's stored protected list). Empty
 *                 when nobody answered within the timeout.
 *   - "result_timeout": seconds to wait for the result — ordered mode default 5 (max 60),
 *                 receiver mode default 30 (max 600, for long sister-app exports).
 *
 * All extras are sent as String extras. Android's boolean-extra caveat (a string
 * "true" reads back as false) doesn't bite here: the Jami video flag travels via
 * the deep link ("?video=1") or the dedicated PLACE_VIDEO_CALL action.
 */
class SendIntentAction : Action {
    override val id = "intent.send"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val action = args["action"]?.trim()?.ifBlank { null }
        val pkg = args["package"]?.trim()?.ifBlank { null }
        val cls = args["class"]?.trim()?.ifBlank { null }
        val data = args["data"]?.trim()?.ifBlank { null }
        val mime = args["mime"]?.trim()?.ifBlank { null }
        val target = args["target"]?.trim()?.ifBlank { null }?.lowercase() ?: TARGET_ACTIVITY

        if (action == null && pkg == null && cls == null) {
            return ActionResult.Failure("nothing to send: set an action, package, or class")
        }
        if (cls != null && pkg == null) {
            return ActionResult.Failure("class requires a package")
        }

        val dataUri: Uri? = if (data != null) {
            runCatching { Uri.parse(data) }.getOrNull()
                ?: return ActionResult.Failure("invalid data URI: $data")
        } else null

        val intent = Intent()
        action?.let { intent.action = it }
        when {
            cls != null -> intent.component = ComponentName(pkg!!, cls)
            pkg != null -> intent.setPackage(pkg)
        }
        // setData() and setType() clear each other, so set them together when both present.
        when {
            dataUri != null && mime != null -> intent.setDataAndType(dataUri, mime)
            dataUri != null -> intent.data = dataUri
            mime != null -> intent.type = mime
        }

        // Fixed extra slots extra1..extra6. Default to a String extra; an optional `extraN_type`
        // (int/long/float/bool) sends a typed extra — some app APIs (e.g. Poweramp's API_COMMAND
        // `rating`) read getIntExtra and ignore a String.
        for (i in 1..MAX_EXTRAS) {
            val key = args["extra${i}_key"]?.trim()?.ifBlank { null } ?: continue
            val value = args["extra${i}_value"] ?: ""
            when (args["extra${i}_type"]?.trim()?.lowercase()) {
                "int" -> value.trim().toIntOrNull()?.let { intent.putExtra(key, it) } ?: intent.putExtra(key, value)
                "long" -> value.trim().toLongOrNull()?.let { intent.putExtra(key, it) } ?: intent.putExtra(key, value)
                "float" -> value.trim().toFloatOrNull()?.let { intent.putExtra(key, it) } ?: intent.putExtra(key, value)
                "bool", "boolean" -> intent.putExtra(key, value.trim().lowercase() in setOf("true", "1", "yes", "on"))
                else -> intent.putExtra(key, value)
            }
        }

        // Optional caller-supplied flags (decimal or 0x-hex), OR'd in.
        args["flags"]?.trim()?.ifBlank { null }?.let { raw ->
            val parsed = parseFlags(raw)
                ?: return ActionResult.Failure("invalid flags: $raw (use a decimal or 0x-hex integer)")
            intent.addFlags(parsed)
        }

        return try {
            when (target) {
                TARGET_ACTIVITY -> {
                    // Starting an Activity from the application (non-Activity) context needs NEW_TASK.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.app.startActivity(intent)
                }
                TARGET_FOREGROUND_SERVICE -> ctx.app.startForegroundService(intent)
                TARGET_SERVICE -> ctx.app.startService(intent)
                TARGET_BROADCAST -> {
                    val resultVar = args["result_var"]?.trim()?.ifBlank { null }
                    // "receiver" reply mode — binder-free round-trip. EMUI severs the ordered-broadcast
                    // RESULT channel between third-party apps AND won't reliably carry a live Binder
                    // (ResultReceiver / PendingIntent) into another app's manifest receiver (verified
                    // 2026-07-23), so the reply can't ride the original broadcast at all. Instead we pass
                    // only plain strings — the reply action, our package, and a random correlation id — and
                    // the target fires a fresh broadcast BACK to our exported IntentReplyReceiver with the
                    // result. The random id (a UUID the target echoes) bounds spoofing. The outgoing send is
                    // ORDERED with FLAG_INCLUDE_STOPPED_PACKAGES — a plain sendBroadcast is dropped for a
                    // backgrounded / freshly-installed (stopped) target on this device.
                    if (resultVar != null && args["reply_via"]?.trim()?.lowercase() == "receiver") {
                        // Receiver mode may wait on genuinely long work in the target app (a sister-app
                        // state export can run for minutes), so it gets a 600 s ceiling; the engine's
                        // per-action budget special-cases intent.send to stay above it (TaskRunner).
                        val timeoutSec = args["result_timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, 600) ?: 30
                        val replyId = java.util.UUID.randomUUID().toString()
                        // 中止 on a visible progress panel abandons the wait at once. Without this a
                        // cancelled batch would still sit out the remaining timeout (up to 600 s) on the
                        // app it was in the middle of — the opposite of "stop now".
                        // Watchdog: give up on a target that has gone silent for this many seconds
                        // (0 / absent = off, wait the full timeout). Every progress broadcast the
                        // target sends is a heartbeat; a target that is ANR-killed mid-export sends
                        // nothing further and would otherwise cost the whole timeout in silence.
                        val watchdogSec = args["watchdog"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
                        var unhookCancel: (() -> Unit)? = null
                        val reply = try {
                            withTimeoutOrNull(timeoutSec * 1000L) {
                                coroutineScope {
                                    val answer = CompletableDeferred<String?>()
                                    IntentReplyBridge.register(replyId) { answer.complete(it) }
                                    unhookCancel = ProgressPanel.onCancel { answer.complete(null) }
                                    intent.putExtra(IntentReplyBridge.EXTRA_REPLY_ACTION, IntentReplyBridge.ACTION_INTENT_REPLY)
                                    intent.putExtra(IntentReplyBridge.EXTRA_REPLY_PACKAGE, ctx.app.packageName)
                                    intent.putExtra(IntentReplyBridge.EXTRA_REPLY_ID, replyId)
                                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                    ctx.app.sendOrderedBroadcast(intent, null)
                                    val watcher = if (watchdogSec == 0) null else launch {
                                        while (isActive) {
                                            delay(WATCHDOG_POLL_MS)
                                            // Judged on PROGRESS, not noise: an app that keeps
                                            // heart-beating the same line while its export hangs is
                                            // not working, and waiting for it costs the full timeout.
                                            val stalled = IntentReplyBridge.stalledFor(replyId) ?: break
                                            if (stalled >= watchdogSec * 1000L) {
                                                val everSpoke = (IntentReplyBridge.silentFor(replyId) ?: 0L) < stalled
                                                // Synthesised as an ERROR: reply so the caller reports a
                                                // real reason instead of a bare timeout.
                                                answer.complete(
                                                    if (everSpoke) "ERROR:target-app-stalled" else "ERROR:target-app-silent",
                                                )
                                                break
                                            }
                                        }
                                    }
                                    try { answer.await() } finally { watcher?.cancel() }
                                }
                            }
                        } finally {
                            unhookCancel?.invoke()
                            IntentReplyBridge.cancel(replyId)
                        }
                        ctx.variables.set(resultVar, reply ?: "")
                        val why = if (reply == null && ProgressPanel.isCancelled()) " (cancelled)" else ""
                        ctx.logger("Send intent (reply): ${action ?: cls ?: pkg} → %$resultVar = ${(reply ?: "").take(80)}$why")
                        return ActionResult.Success
                    }
                    if (resultVar != null) {
                        // Ordered broadcast with a result: the receiver's setResultData() comes back to
                        // us — the round-trip that lets a task VERIFY a sister app's state (e.g. its
                        // stored protected-contacts list) instead of fire-and-forget.
                        val timeoutSec = args["result_timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, 60) ?: 5
                        val resultData = withTimeoutOrNull(timeoutSec * 1000L) {
                            suspendCancellableCoroutine { cont ->
                                ctx.app.sendOrderedBroadcast(
                                    intent,
                                    null,
                                    object : BroadcastReceiver() {
                                        override fun onReceive(c: Context?, i: Intent?) {
                                            if (cont.isActive) cont.resume(this.resultData.orEmpty())
                                        }
                                    },
                                    null,
                                    0,      // initial code — a responding receiver overrides it
                                    null,   // initial data — null so "" is distinguishable as "answered empty"
                                    null,
                                )
                            }
                        }
                        ctx.variables.set(resultVar, resultData ?: "")
                        ctx.logger("Send intent (ordered): ${action ?: cls ?: pkg} → %$resultVar = ${(resultData ?: "").take(80)}")
                        return ActionResult.Success
                    }
                    ctx.app.sendBroadcast(intent)
                }
                else -> return ActionResult.Failure(
                    "unknown target: $target (use activity / foreground-service / service / broadcast)"
                )
            }
            ctx.logger("Send intent: ${action ?: cls ?: pkg} → $target")
            ActionResult.Success
        } catch (ex: Exception) {
            ActionResult.Failure("send intent failed: ${ex.message}", ex)
        }
    }

    private fun parseFlags(raw: String): Int? =
        if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toLongOrNull(16)?.toInt()
        } else {
            raw.toLongOrNull()?.toInt()
        }

    companion object {
        private const val WATCHDOG_POLL_MS = 5_000L
        private const val MAX_EXTRAS = 6
        private const val TARGET_ACTIVITY = "activity"
        private const val TARGET_FOREGROUND_SERVICE = "foreground-service"
        private const val TARGET_SERVICE = "service"
        private const val TARGET_BROADCAST = "broadcast"
    }
}
