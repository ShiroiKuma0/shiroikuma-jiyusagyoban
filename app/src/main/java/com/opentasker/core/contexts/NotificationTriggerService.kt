package com.opentasker.core.contexts

import android.app.ActivityOptions
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.logging.AppLogger

class NotificationTriggerService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        // Go quiet while the app is stopped. requestUnbind() — unlike disabling the service — keeps the
        // user's grant, so [requestRebindIfEnabled] can bring it back with no trip to system settings.
        if (EngineShutdown.isStopped(this)) {
            AppLogger.info(TAG, "Notification listener unbinding — the app is stopped")
            runCatching { requestUnbind() }
        }
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

    /**
     * Do what a tap on [pkg]'s newest notification does: send its content intent, then clear it if it
     * auto-cancels. [text] narrows the pick to one whose title or body contains it (ignoring case).
     * Returns the key of the notification opened, or null when none matched or it has no tap action.
     */
    fun openNewest(pkg: String, text: String): String? {
        val active = runCatching { activeNotifications }.getOrNull() ?: return null
        val sbn = active.filter { it.packageName == pkg && it.notification.contentIntent != null }
            .filter { text.isEmpty() || it.mentions(text) }
            .maxByOrNull { it.postTime } ?: return null
        val n = sbn.notification
        // Hik-Connect and its like start an Activity from here while we sit in the background. Our
        // overlay grant lets us do that; from Android 14 the grant has to be passed on explicitly.
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            ).toBundle()
        } else {
            null
        }
        n.contentIntent.send(this, 0, null, null, null, null, options)
        if (n.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { cancelNotification(sbn.key) }
        return sbn.key
    }

    private fun StatusBarNotification.mentions(text: String): Boolean {
        val extras = notification.extras
        return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .any { extras.getCharSequence(it)?.contains(text, ignoreCase = true) == true }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (EngineShutdown.isStopped(this)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        // Marker set by a sister app on a protected-contact "vague" notification — the key is PER-PACKAGE
        // ("<pkg>.protected", e.g. shiroikuma.jami.protected / shiroikuma.arcanechat.protected), so every
        // sister app that adopts the convention works with no per-app change here. 通知明滅 reads it via
        // %NOTIF_PROTECTED to blink for it without any visible content.
        val isProtected = extras.getBoolean("${sbn.packageName}.protected", false)

        val accepted = NotificationContextEvents.publish(
            packageName = sbn.packageName.orEmpty(),
            title = title,
            body = body,
            ongoing = sbn.isOngoing,
            isProtected = isProtected,
            channel = sbn.notification.channelId.orEmpty(),
        )
        AppLogger.debug(
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

        /**
         * Let go of the binding as part of "Exit app fully". requestUnbind() keeps the user's grant —
         * unlike disabling the listener, which would need a trip through system settings to undo — so
         * [requestRebindIfEnabled] can pick it straight back up. NOT used by an engine restart, which
         * wants the listener to stay where it is.
         */
        fun unbindForShutdown() {
            val live = instance ?: return
            AppLogger.info(TAG, "Notification listener unbinding for shutdown")
            runCatching { live.requestUnbind() }
        }

        /**
         * Ask the system to bind the listener again after a shutdown unbound it. The grant was never
         * given up, so this is silent — no system-settings trip for the user. A no-op when the listener
         * was never enabled, or is already bound.
         */
        fun requestRebindIfEnabled(context: Context) {
            if (instance != null) return
            runCatching {
                requestRebind(ComponentName(context.applicationContext, NotificationTriggerService::class.java))
            }.onFailure { AppLogger.debug(TAG, "Notification listener rebind not possible: ${it.message}") }
        }
    }
}
