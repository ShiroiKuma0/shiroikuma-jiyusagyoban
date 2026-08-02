package com.opentasker.core.contexts

import com.opentasker.core.diagnostics.AdvancedProtectionReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Emits a pulse whenever Android Advanced Protection changes state. */
object AdvancedProtectionContextEvents {
    const val EVENT_ADVANCED_PROTECTION = "advanced_protection"
    const val STATE_ENABLED = "enabled"
    const val STATE_DISABLED = "disabled"

    val events: Flow<ContextEvent> = AdvancedProtectionReader.changes.map(::buildEvent)

    fun buildEvent(enabled: Boolean): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_ADVANCED_PROTECTION,
            "state" to if (enabled) STATE_ENABLED else STATE_DISABLED,
            "enabled" to enabled.toString(),
        ),
    )
}
