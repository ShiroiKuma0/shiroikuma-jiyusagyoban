package com.sysadmindoc.ruledeck.core.contexts

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sysadmindoc.ruledeck.core.logging.AppLogger

class NotificationTriggerService : NotificationListenerService() {
    /**
     * Where a posted notification goes: the bus the engine listens on. An instrumented test runs
     * inside the app on a real phone, so it swaps in a recorder rather than fire a notification
     * profile there.
     */
    internal var publish: (packageName: String, title: CharSequence?, body: CharSequence?) -> Boolean =
        NotificationContextEvents::publish

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        val accepted = publish(sbn.packageName.orEmpty(), title, body)
        AppLogger.debug(
            TAG,
            "Notification event accepted=$accepted package=${sbn.packageName} titleChars=${title?.length ?: 0} bodyChars=${body?.length ?: 0}",
        )
    }

    companion object {
        private const val TAG = "NotificationTrigger"
    }
}
