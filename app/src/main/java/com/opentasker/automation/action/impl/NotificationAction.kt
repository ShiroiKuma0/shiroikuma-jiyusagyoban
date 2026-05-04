package com.opentasker.automation.action.impl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opentasker.automation.core.ActionDefinition
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult

/**
 * Notification action that displays a notification to the user.
 * Supports title, body, sound, and vibration.
 */
class NotificationAction(private val context: Context) : ActionDefinition {
    override val id = "notification"
    override val displayName = "Show Notification"

    init {
        // Create notification channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Automation Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from automation rules"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override suspend fun execute(config: ActionConfig): ActionResult {
        return try {
            val startTime = System.currentTimeMillis()
            val title = config.config["title"] as String? ?: "OpenTasker"
            val body = config.config["body"] as String? ?: "Rule executed"
            val sound = config.config["sound"] as Boolean? ?: true
            val vibrate = config.config["vibrate"] as Boolean? ?: true

            showNotification(title, body, sound, vibrate)

            ActionResult(
                success = true,
                message = "Notification shown",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "Failed to show notification: ${e.message}",
                executionTimeMs = 0,
                stackTrace = e.stackTraceToString()
            )
        }
    }

    private fun showNotification(title: String, body: String, sound: Boolean, vibrate: Boolean) {
        val notificationId = System.currentTimeMillis().toInt()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (vibrate) {
            builder.setVibrate(longArrayOf(0, 250, 250, 250))
        }

        if (sound) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(soundUri)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "automation_notifications"
    }
}
