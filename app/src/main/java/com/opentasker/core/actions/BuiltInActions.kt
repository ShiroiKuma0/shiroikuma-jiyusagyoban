package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import kotlinx.coroutines.suspendCancellableCoroutine
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.isArgumentSensitive
import com.opentasker.core.model.VariableNamePolicy
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

        val taskReferences = (1..NotificationTaskBindings.BUTTON_COUNT).mapNotNull { buttonIndex ->
            NotificationTaskBindings.parse(args, buttonIndex)?.let { buttonIndex to it }
        }
        val taskCandidates = if (taskReferences.isEmpty()) {
            emptyList()
        } else {
            OpenTaskerApp_NoHilt.db.taskDao().getAll().map { NotificationTaskCandidate(it.id, it.name) }
        }

        for ((i, reference) in taskReferences) {
            val resolution = NotificationTaskBindings.resolve(reference, taskCandidates)
            if (resolution !is NotificationTaskResolution.Bound) {
                return ActionResult.Failure(
                    "Notification button $i is not runnable: ${NotificationTaskBindings.failureMessage(resolution)}",
                )
            }
            val label = args["button${i}_label"] ?: resolution.task.name
            // A unique request code per button guarantees two notifications (even with adjacent
            // ids) never share a PendingIntent slot, so FLAG_UPDATE_CURRENT can't overwrite an
            // older button intent and fire the wrong task.
            val requestCode = PendingIntentRequestCodes.next()
            val buttonIntent = Intent(ctx.app, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON
                putExtra(NotificationActionReceiver.EXTRA_TASK_ID, resolution.task.id)
                putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, label)
                putExtra("_req", requestCode)
            }
            val pi = PendingIntent.getBroadcast(
                ctx.app,
                requestCode,
                buttonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, label, pi)
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

internal object NotificationChannels {
    data class ChannelDef(
        val id: String,
        val name: String,
        val importance: Int,
    )

    private val channels = mapOf(
        "quiet" to ChannelDef("opentasker.quiet", "OpenTasker quiet", NotificationManager.IMPORTANCE_LOW),
        "default" to ChannelDef("opentasker.actions", "OpenTasker actions", NotificationManager.IMPORTANCE_DEFAULT),
        "urgent" to ChannelDef("opentasker.urgent", "OpenTasker urgent", NotificationManager.IMPORTANCE_HIGH),
    )

    fun resolve(key: String): ChannelDef =
        channels[key.trim().lowercase()] ?: channels.getValue("default")

    fun allKeys(): Set<String> = channels.keys
}

/**
 * Variable set action.
 *
 * Args:
 *   - "name": variable name, or a dotted/bracketed path for nested JSON writes
 *     (e.g. "config.theme", "items[0]", "Config.user.name")
 *   - "value": new value (supports %expansion)
 */
class SetVariableAction : Action {
    override val id = "var.set"
    override val category = ActionCategory.VARIABLE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"] ?: return ActionResult.Failure("missing name")
        val value = args["value"] ?: ""
        if (name.contains('.') || name.contains('[')) {
            if (!ctx.variables.setAtPath(name, value)) {
                return ActionResult.Failure("invalid path: $name")
            }
            val baseName = name.takeWhile { it != '.' && it != '[' }
            val loggedValue = if (ctx.isArgumentSensitive("value") || ctx.variables.isSensitive(baseName)) {
                REDACTED_VARIABLE_VALUE
            } else {
                value
            }
            ctx.logger("Set path \$$name = $loggedValue")
        } else {
            ctx.variables.set(name, value)
            val loggedValue = if (ctx.isArgumentSensitive("value") || ctx.variables.isSensitive(name)) {
                REDACTED_VARIABLE_VALUE
            } else {
                value
            }
            ctx.logger("Set \$$name = $loggedValue")
        }
        return ActionResult.Success
    }
}

/**
 * Persist a variable to global scope.
 *
 * Copies the current value of a variable into the global namespace
 * so it survives across task invocations within the same service lifetime.
 *
 * Args:
 *   - "name": source variable name (local or global)
 *   - "global_name": target global variable name (auto-uppercased if needed)
 */
class PersistVariableAction : Action {
    override val id = "var.persist"
    override val category = ActionCategory.VARIABLE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawName = args["name"] ?: return ActionResult.Failure("missing name")
        val name = VariableNamePolicy.normalize(rawName)
            ?: return ActionResult.Failure("invalid variable name '$rawName'")
        val rawGlobalName = args["global_name"] ?: name
        val globalName = VariableNamePolicy.promoteToGlobal(rawGlobalName)
            ?: return ActionResult.Failure("invalid global variable name '$rawGlobalName'")
        val value = ctx.variables.get(name)
            ?: return ActionResult.Failure("variable '$name' is not set")
        val sensitive = ctx.variables.isSensitive(name)
        ctx.variables.set(globalName, value, sensitive = sensitive)
        ctx.logger("Persist \$$name → \$$globalName = ${if (sensitive) REDACTED_VARIABLE_VALUE else value}")
        return ActionResult.Success
    }
}

private const val REDACTED_VARIABLE_VALUE = "<redacted>"

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
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("missing text argument")
        if (text.length > MAX_TTS_CHARS) {
            return ActionResult.Failure("text exceeds $MAX_TTS_CHARS character limit (${text.length})")
        }
        AndroidAudioHardening.failureIfIneligible(ctx, "text-to-speech output")?.let { return it }
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
                val queued = engine.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "opentasker_say")
                if (queued != android.speech.tts.TextToSpeech.SUCCESS) {
                    // No utterance callback will ever fire for a failed queue; fail fast
                    // instead of burning the whole action budget on a silent timeout.
                    completeOnce(ActionResult.Failure("TTS could not queue the utterance"))
                }
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
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis")
        val ms = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (ms < 0) {
            return ActionResult.Failure("wait duration must be non-negative")
        }
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
