package com.opentasker.core.contexts

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationTriggerService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Cancel every clearable active notification from [pkg]. Used by the notify.dismiss action so
     *  entering an app removes its notification (the 通知明滅 edge-light off-trigger). Returns the count. */
    fun dismissPackage(pkg: String): Int {
        val active = runCatching { activeNotifications }.getOrNull() ?: return 0
        var n = 0
        for (sbn in active) {
            if (sbn.packageName == pkg && sbn.isClearable) {
                runCatching { cancelNotification(sbn.key) }.onSuccess { n++ }
            }
        }
        return n
    }

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

        /** The connected listener instance, or null if the service isn't bound (no notification access). */
        @Volatile
        var instance: NotificationTriggerService? = null
            private set
    }
}
