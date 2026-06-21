package com.opentasker.core.contexts

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationTriggerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        val accepted = NotificationContextEvents.publish(
            packageName = sbn.packageName.orEmpty(),
            title = title,
            body = body,
            ongoing = sbn.isOngoing,
        )
        Log.d(
            TAG,
            "Notification event accepted=$accepted package=${sbn.packageName} titleChars=${title?.length ?: 0} bodyChars=${body?.length ?: 0}",
        )
    }

    companion object {
        private const val TAG = "NotificationTrigger"
    }
}
