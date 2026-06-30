package com.opentasker.core.contexts

import com.opentasker.core.engine.variables.PersistentGlobalScope
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
    ): Boolean {
        // Expose the latest notification's fields as super-globals so an enter task can read them
        // (e.g. pick the edge-blink colour by %NOTIF_PACKAGE, skip ongoing ones via %NOTIF_ONGOING),
        // mirroring %APP_PACKAGE.
        val pkg = packageName.trim()
        PersistentGlobalScope.set(0L, "NOTIF_PACKAGE", pkg)
        PersistentGlobalScope.set(0L, "NOTIF_TITLE", sanitizeText(title))
        PersistentGlobalScope.set(0L, "NOTIF_BODY", sanitizeText(body))
        PersistentGlobalScope.set(0L, "NOTIF_ONGOING", ongoing.toString())
        return notifications.tryEmit(buildEvent(pkg, title, body, ongoing))
    }

    fun buildEvent(
        packageName: String,
        title: CharSequence?,
        body: CharSequence?,
        ongoing: Boolean = false,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "notification",
            "package" to packageName.trim(),
            "title" to sanitizeText(title),
            "body" to sanitizeText(body),
            "ongoing" to ongoing.toString(),
        ),
        // Per-invocation snapshot under the SAME names as the published super-globals, so a queued
        // task (e.g. 通知明滅 in QUEUED mode) reads THIS notification's values, not a later one's.
        vars = mapOf(
            "NOTIF_PACKAGE" to packageName.trim(),
            "NOTIF_TITLE" to sanitizeText(title),
            "NOTIF_BODY" to sanitizeText(body),
            "NOTIF_ONGOING" to ongoing.toString(),
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
