package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import com.opentasker.core.contexts.NotificationTriggerService
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.AndroidAudioHardening
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification action — display a toast or heads-up notification.
 *
 * Args:
 *   - "title": notification title
 *   - "text": notification body
 *   - "duration": "short" or "long" (Toast duration only)
 */
class NotifyAction : Action {
    override val id = "notify.show"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val title = args["title"] ?: "Notification"
        val text = args["text"] ?: ""
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Notification permission is not granted")
        }

        val nm = ctx.app.getSystemService(NotificationManager::class.java)
        val channelKey = args["channel"] ?: "default"
        val channelDef = NotificationChannels.resolve(channelKey)
        nm.createNotificationChannel(
            NotificationChannel(channelDef.id, channelDef.name, channelDef.importance),
        )

        val channel = nm.getNotificationChannel(channelDef.id)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            ctx.logger("Warning: channel '${channelDef.name}' is blocked by the user")
            return ActionResult.Failure("Notification channel '${channelDef.name}' is blocked by the user; open system settings to unblock")
        }

        val persistent = args["persistent"]?.toBooleanStrictOrNull() ?: false
        val tag = args["tag"]
        val notifId = args["id"]?.toIntOrNull() ?: nextNotificationId.getAndIncrement()

        val builder = NotificationCompat.Builder(ctx.app, channelDef.id)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!persistent)
            .setOngoing(persistent)

        for (i in 1..3) {
            val taskName = args["button${i}_task"] ?: continue
            val label = args["button${i}_label"] ?: taskName
            val buttonIntent = Intent(ctx.app, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON
                putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, taskName)
                putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, label)
                putExtra("_req", (notifId.hashCode() * 31 + i) and 0x7FFFFFFF)
            }
            val requestCode = (notifId.hashCode() * 31 + i) and 0x7FFFFFFF
            val pi = PendingIntent.getBroadcast(
                ctx.app,
                requestCode,
                buttonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, pi)
        }

        // Body tap (contentIntent) runs a task — clickable in the collapsed view too, unlike action
        // buttons which only show when the notification is expanded.
        args["tap_task"]?.takeIf { it.isNotBlank() }?.let { taskName ->
            val req = (notifId.hashCode() * 31 + 99) and 0x7FFFFFFF
            val tapIntent = Intent(ctx.app, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON
                putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, taskName)
                putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, title)
                putExtra("_req", req)
            }
            builder.setContentIntent(
                PendingIntent.getBroadcast(
                    ctx.app, req, tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        val notification = builder.build()

        return try {
            NotificationManagerCompat.from(ctx.app).notify(tag, notifId, notification)
            ctx.logger("Notify: $title | $text (channel=${channelDef.name}, id=$notifId${if (tag != null) ", tag=$tag" else ""})")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("notification failed: ${ex.message}", ex)
        }
    }

    companion object {
        private val nextNotificationId = AtomicInteger(10_000)
    }
}

class NotifyCancelAction : Action {
    override val id = "notify.cancel"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val tag = args["tag"]
        val notifId = args["id"]?.toIntOrNull()
        val nm = NotificationManagerCompat.from(ctx.app)

        return when {
            tag != null && notifId != null -> {
                nm.cancel(tag, notifId)
                ctx.logger("Cancel notification: tag=$tag, id=$notifId")
                ActionResult.Success
            }
            notifId != null -> {
                nm.cancel(notifId)
                ctx.logger("Cancel notification: id=$notifId")
                ActionResult.Success
            }
            tag != null -> {
                val mgr = ctx.app.getSystemService(NotificationManager::class.java)
                val cancelled = mgr.activeNotifications.filter { it.tag == tag }
                cancelled.forEach { nm.cancel(it.tag, it.id) }
                ctx.logger("Cancel notification: tag=$tag (${cancelled.size} cancelled)")
                ActionResult.Success
            }
            else -> ActionResult.Failure("Specify at least one of 'tag' or 'id' to cancel")
        }
    }
}

/** Dismiss every clearable notification from another app, by package — needs notification access. */
class NotifyDismissAction : Action {
    override val id = "notify.dismiss"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("Specify 'package' to dismiss notifications from")
        val listener = NotificationTriggerService.instance
            ?: return ActionResult.Failure("Notification access not granted (listener not connected)")
        val n = listener.dismissPackage(pkg)
        ctx.logger("Dismissed $n notification(s) from $pkg")
        return ActionResult.Success
    }
}

internal object NotificationChannels {
    data class ChannelDef(
        val id: String,
        val name: String,
        val importance: Int,
    )

    private val channels = mapOf(
        "quiet" to ChannelDef("opentasker.quiet", "白い熊 自由作業盤 quiet", NotificationManager.IMPORTANCE_LOW),
        "default" to ChannelDef("opentasker.actions", "白い熊 自由作業盤 actions", NotificationManager.IMPORTANCE_DEFAULT),
        "urgent" to ChannelDef("opentasker.urgent", "白い熊 自由作業盤 urgent", NotificationManager.IMPORTANCE_HIGH),
    )

    fun resolve(key: String): ChannelDef =
        channels[key.trim().lowercase()] ?: channels.getValue("default")

    fun allKeys(): Set<String> = channels.keys
}

/**
 * Variable set action.
 *
 * Args:
 *   - "name": variable name
 *   - "value": new value (supports %expansion)
 */
class SetVariableAction : Action {
    override val id = "var.set"
    override val category = ActionCategory.VARIABLE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"] ?: return ActionResult.Failure("missing name")
        val value = args["value"] ?: ""
        ctx.variables.set(name, value)
        ctx.logger("Set \$$name = $value")
        return ActionResult.Success
    }
}

/**
 * Say (text-to-speech) action.
 *
 * Args:
 *   - "text": text to speak
 */
class SayAction : Action {
    override val id = "tts.speak"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (AndroidAudioHardening.isRestricted()) {
            return AndroidAudioHardening.ttsFailure()
        }
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("missing text argument")
        if (text.length > MAX_TTS_CHARS) {
            return ActionResult.Failure("text exceeds $MAX_TTS_CHARS character limit (${text.length})")
        }
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun completeOnce(result: ActionResult) {
                if (resumed.compareAndSet(false, true)) {
                    tts?.shutdown()
                    cont.resumeWith(Result.success(result))
                }
            }
            tts = android.speech.tts.TextToSpeech(ctx.app) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    completeOnce(ActionResult.Failure("TTS engine initialization failed (status=$status)"))
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { completeOnce(ActionResult.Success) }
                    @Deprecated("Deprecated in API 21+")
                    override fun onError(utteranceId: String?) { completeOnce(ActionResult.Failure("TTS utterance failed")) }
                })
                ctx.logger("TTS: ${text.take(80)}${if (text.length > 80) "..." else ""}")
                engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "opentasker_say")
            }
            cont.invokeOnCancellation { tts.shutdown() }
        }
    }

    companion object {
        private const val MAX_TTS_CHARS = 4000
    }
}

/**
 * Wait action — pause task execution.
 *
 * Args:
 *   - "millis": milliseconds to wait
 */
class WaitAction : Action {
    override val id = "flow.wait"
    override val category = ActionCategory.FLOW

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ms = args["millis"]?.toLongOrNull() ?: 0L
        if (ms > MAX_WAIT_MS) {
            return ActionResult.Failure("wait duration ${ms}ms exceeds maximum of ${MAX_WAIT_MS / 60_000} minutes")
        }
        if (ms > 0) {
            ctx.logger("Wait ${ms}ms")
            kotlinx.coroutines.delay(ms)
        }
        return ActionResult.Success
    }

    companion object {
        private const val MAX_WAIT_MS = 1_800_000L // 30 minutes
    }
}

/**
 * Intent launch action.
 *
 * Args:
 *   - "package": target package
 *   - "action": intent action (optional, defaults to MAIN)
 *   - "category": intent category (optional)
 */
class LaunchIntentAction : Action {
    override val id = "intent.launch"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        val action = args["action"]?.ifBlank { null }
        val category = args["category"]?.ifBlank { null }
        return try {
            val intent = if (action == null) {
                ctx.app.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return ActionResult.Failure("app not found: $pkg")
            } else {
                Intent(action).setPackage(pkg)
            }.apply {
                category?.let(::addCategory)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.app.startActivity(intent)
            ctx.logger("Intent launch: $pkg")
            ActionResult.Success
        } catch (ex: Exception) {
            ActionResult.Failure("intent launch failed: ${ex.message}", ex)
        }
    }
}
