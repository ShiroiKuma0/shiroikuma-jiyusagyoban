package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bridges CompanionDeviceService callbacks into low-power companion presence pulses. */
object CompanionContextEvents {
    const val EVENT_COMPANION_PRESENCE = "companion_presence"
    const val STATE_PRESENT = "present"
    const val STATE_ABSENT = "absent"
    const val UNKNOWN_ASSOCIATION = "Unknown"

    private val events_ = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    fun publishPresent(associationId: String, label: String = "") {
        events_.tryEmitPulse("companion-device", buildEvent(STATE_PRESENT, associationId, label))
    }

    fun publishAbsent(associationId: String, label: String = "") {
        events_.tryEmitPulse("companion-device", buildEvent(STATE_ABSENT, associationId, label))
    }

    fun buildEvent(state: String, associationId: String, label: String = ""): ContextEvent =
        ContextEvent(
            type = "event",
            matched = true,
            metadata = buildMap {
                put("event", EVENT_COMPANION_PRESENCE)
                put("state", state)
                put("associationId", associationId.ifBlank { UNKNOWN_ASSOCIATION })
                if (label.isNotBlank()) put("label", label.take(MAX_LABEL_CHARS))
                put("observedAtEpochMs", System.currentTimeMillis().toString())
            },
        )

    private const val MAX_LABEL_CHARS = 160
}
