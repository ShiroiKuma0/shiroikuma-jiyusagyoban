package com.opentasker.core.contexts

import com.opentasker.core.engine.variables.PersistentGlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Device-orientation events ("orientation"), fired when the screen settles into a new quadrant
 * (portrait / landscape / reverse-portrait / reverse-landscape). The current orientation is also
 * exposed as the super-global %DEVICE_ORIENTATION so the triggered task can read it. An EVENT context
 * may narrow with config orientation=portrait,landscape (CSV) — or omit it to fire on any change.
 */
object OrientationContextEvents {
    private val orientations = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContextEvent> = orientations.asSharedFlow()

    fun publish(orientation: String) {
        PersistentGlobalScope.set(0L, "DEVICE_ORIENTATION", orientation)
        orientations.tryEmit(
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf("event" to "orientation", "orientation" to orientation),
            ),
        )
    }
}
