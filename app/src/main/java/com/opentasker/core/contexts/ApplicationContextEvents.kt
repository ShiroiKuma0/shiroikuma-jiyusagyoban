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
    @Volatile private var latestForegroundPackage: String? = null
    @Volatile private var latestForegroundComponent: String? = null

    val events: Flow<ContextEvent> = flow {
        emit(
            ContextEvent(
                TYPE,
                matched = false,
                metadata = mapOf(
                    "foreground" to "",
                    "component" to "",
                    "component_status" to "unavailable",
                ),
            ),
        )
        emitAll(foregroundEvents.asSharedFlow())
    }

    fun publishForeground(packageName: String, className: String? = null): Boolean {
        val normalized = packageName.trim()
        if (normalized.isBlank()) return false
        val normalizedComponent = className?.trim().orEmpty()
        latestForegroundPackage = normalized
        latestForegroundComponent = normalizedComponent.ifBlank { null }
        // Fork: `current` is the package the state.get action reads; keep it in step with upstream's
        // component-aware tracking above.
        current = normalized
        return foregroundEvents.tryEmit(
            ContextEvent(
                type = TYPE,
                matched = true,
                metadata = mapOf(
                    "foreground" to normalized,
                    "component" to normalizedComponent,
                    "component_status" to if (normalizedComponent.isBlank()) "unavailable" else "available",
                ),
            ),
        )
    }

    /** Latest package shown by the Context Inspector, offered as an explicit editor shortcut. */
    fun latestObservedPackage(): String? = latestForegroundPackage

    /** Latest Activity class shown by the Context Inspector, when the OEM reported one. */
    fun latestObservedComponent(): String? = latestForegroundComponent
}
