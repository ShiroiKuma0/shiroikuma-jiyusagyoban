package com.opentasker.core.contexts

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.opentasker.core.logging.AppLogger

class NotificationTriggerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val posted = postedNotification(sbn)

        val accepted = NotificationContextEvents.publish(
            packageName = posted.packageName,
            title = posted.title,
            body = posted.body,
        )
        AppLogger.debug(
            TAG,
            "Notification event accepted=$accepted package=${posted.packageName} titleChars=${posted.title?.length ?: 0} bodyChars=${posted.body?.length ?: 0}",
        )
    }

    companion object {
        private const val TAG = "NotificationTrigger"
    }
}

internal data class PostedNotification(val packageName: String, val title: CharSequence?, val body: CharSequence?)

/**
 * What a posted notification tells the matcher: its sender, its title, and its text or else its
 * big text. Split from the service so a test can read real extras without publishing to the bus
 * the engine listens on.
 */
internal fun postedNotification(sbn: StatusBarNotification): PostedNotification {
    val extras = sbn.notification.extras
    return PostedNotification(
        packageName = sbn.packageName.orEmpty(),
        title = extras.getCharSequence(Notification.EXTRA_TITLE),
        body = extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
    )
}
