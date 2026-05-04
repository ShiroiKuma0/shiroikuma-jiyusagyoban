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
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

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
            NotificationManagerCompat.from(ctx.app).notify(System.currentTimeMillis().toInt(), notification)
            ctx.logger("Notify: $title | $text")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("notification failed: ${ex.message}", ex)
        }
    }

    companion object {
        private const val CHANNEL_ID = "opentasker.actions"
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
        val text = args["text"] ?: ""
        ctx.logger("TTS: $text")
        return ActionResult.Failure("Text-to-speech action is not implemented yet")
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
        if (ms > 0) {
            ctx.logger("Wait ${ms}ms")
            kotlinx.coroutines.delay(ms)
        }
        return ActionResult.Success
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
