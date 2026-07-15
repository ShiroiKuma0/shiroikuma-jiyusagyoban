package com.opentasker.core.contexts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

object ApplicationContextEvents {
    private const val TYPE = "app"

    /** Latest foreground package (last value published by the usage monitor). "" until first seen.
     *  Exposed so a task can read it via `state.get` (key "app") to branch on the foreground app. */
    @Volatile
    var current: String = ""
        private set

    private val foregroundEvents = MutableSharedFlow<ContextEvent>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    val events: Flow<ContextEvent> = flow {
        emit(ContextEvent(TYPE, matched = false, metadata = mapOf("foreground" to "")))
        emitAll(foregroundEvents.asSharedFlow())
    }

    fun publishForeground(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        current = normalized
        return foregroundEvents.tryEmit(
            ContextEvent(
                type = TYPE,
                matched = true,
                metadata = mapOf("foreground" to normalized),
            ),
        )
    }
}
