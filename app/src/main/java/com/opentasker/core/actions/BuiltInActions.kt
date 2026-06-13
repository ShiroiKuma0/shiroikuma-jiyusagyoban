package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
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

        val notificationManager = ctx.app.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenTasker actions", NotificationManager.IMPORTANCE_DEFAULT),
        )

        val notification = NotificationCompat.Builder(ctx.app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()

        return try {
            val notifId = nextNotificationId.getAndIncrement()
            NotificationManagerCompat.from(ctx.app).notify(notifId, notification)
            ctx.logger("Notify: $title | $text")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("notification failed: ${ex.message}", ex)
        }
    }

    companion object {
        private const val CHANNEL_ID = "opentasker.actions"
        private val nextNotificationId = AtomicInteger(10_000)
    }
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
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("missing text argument")
        if (text.length > MAX_TTS_CHARS) {
            return ActionResult.Failure("text exceeds $MAX_TTS_CHARS character limit (${text.length})")
        }
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            tts = android.speech.tts.TextToSpeech(ctx.app) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    cont.resumeWith(Result.success(ActionResult.Failure("TTS engine initialization failed (status=$status)")))
                    return@TextToSpeech
                }
                val engine = tts ?: return@TextToSpeech
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        cont.resumeWith(Result.success(ActionResult.Success))
                    }
                    @Deprecated("Deprecated in API 21+")
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        cont.resumeWith(Result.success(ActionResult.Failure("TTS utterance failed")))
                    }
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
