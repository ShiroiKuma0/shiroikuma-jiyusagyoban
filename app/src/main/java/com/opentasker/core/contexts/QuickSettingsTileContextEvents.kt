package com.opentasker.core.contexts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object QuickSettingsTileContextEvents {
    const val EVENT_TILE_CLICKED = "tile_clicked"

    private val tileEvents = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: Flow<ContextEvent> = tileEvents.asSharedFlow()

    fun publishTileClicked(active: Boolean): Boolean {
        return tileEvents.tryEmit(buildEvent(active))
    }

    fun buildEvent(
        active: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_TILE_CLICKED,
            "tileActive" to active.toString(),
            "observedAtEpochMs" to nowMs.toString(),
        ),
    )
}
