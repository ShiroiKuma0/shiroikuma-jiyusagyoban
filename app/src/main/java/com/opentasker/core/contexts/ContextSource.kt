package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Base interface for a source of context events (e.g., time, location, app state).
 *
 * Implementations are registered in [ContextSourceRegistry] and emit events
 * whenever their match state changes.
 */
interface ContextSource {
    val type: String
    fun events(app: Context): Flow<ContextEvent>
}

object ContextSourceRegistry {
    private val byType = mutableMapOf<String, ContextSource>()

    fun register(source: ContextSource) { byType[source.type] = source }
    fun get(type: String): ContextSource? = byType[type]
    fun all(): Collection<ContextSource> = byType.values
}
