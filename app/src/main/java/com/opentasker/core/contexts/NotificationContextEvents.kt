package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object NotificationContextEvents {
    private const val MAX_TEXT_CHARS = 240
    private val notifications = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 64,
    )

    val events: SharedFlow<ContextEvent> = notifications.asSharedFlow()

    fun publish(
        packageName: String,
        title: CharSequence?,
        body: CharSequence?,
        ongoing: Boolean = false,
        isProtected: Boolean = false,
        channel: String = "",
    ): Boolean {
        // The notification's fields are threaded PER-INVOCATION to the enter task via ContextEvent.vars
        // (see buildEvent) — NOT persisted as super-globals. The only reader is the notification enter task
        // (通知明滅点灯), which receives these locals; persisting them just cluttered the global namespace.
        val pkg = packageName.trim()
        return notifications.tryEmit(buildEvent(pkg, title, body, ongoing, isProtected, channel))
    }

    fun buildEvent(
        packageName: String,
        title: CharSequence?,
        body: CharSequence?,
        ongoing: Boolean = false,
        isProtected: Boolean = false,
        channel: String = "",
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "notification",
            "package" to packageName.trim(),
            "title" to sanitizeText(title),
            "body" to sanitizeText(body),
            "ongoing" to ongoing.toString(),
            "protected" to isProtected.toString(),
            "channel" to channel.trim(),
        ),
        // Per-invocation snapshot under the SAME names as the published super-globals, so a queued
        // task (e.g. 通知明滅 in QUEUED mode) reads THIS notification's values, not a later one's.
        vars = mapOf(
            "NOTIF_PACKAGE" to packageName.trim(),
            "NOTIF_TITLE" to sanitizeText(title),
            "NOTIF_BODY" to sanitizeText(body),
            "NOTIF_ONGOING" to ongoing.toString(),
            "NOTIF_PROTECTED" to isProtected.toString(),
            // The posting app's notification-channel id — the locale-proof way to tell housekeeping
            // channels (e.g. Jami's shiroikuma_watchdog) from real message/call channels.
            "NOTIF_CHANNEL" to channel.trim(),
        ),
    )

    fun sanitizeText(value: CharSequence?): String =
        value
            ?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            .orEmpty()
}
