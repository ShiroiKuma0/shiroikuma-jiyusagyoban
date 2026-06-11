package com.opentasker.core.contexts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

object ApplicationContextEvents {
    private const val TYPE = "app"

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
        return foregroundEvents.tryEmit(
            ContextEvent(
                type = TYPE,
                matched = true,
                metadata = mapOf("foreground" to normalized),
            ),
        )
    }
}
