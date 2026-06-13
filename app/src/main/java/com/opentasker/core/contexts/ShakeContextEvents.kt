package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ShakeContextEvents {
    private val shakes = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<ContextEvent> = shakes.asSharedFlow()

    fun publish(magnitude: Float) {
        shakes.tryEmit(
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf(
                    "event" to "shake",
                    "magnitude" to "%.1f".format(magnitude),
                ),
            ),
        )
    }
}
