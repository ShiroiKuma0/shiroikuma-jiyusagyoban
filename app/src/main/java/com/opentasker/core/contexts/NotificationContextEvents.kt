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
    ): Boolean = notifications.tryEmit(buildEvent(packageName, title, body))

    fun buildEvent(
        packageName: String,
        title: CharSequence?,
        body: CharSequence?,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "notification",
            "package" to packageName.trim(),
            "title" to sanitizeText(title),
            "body" to sanitizeText(body),
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
