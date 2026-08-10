package com.opentasker.core.contexts

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Collections

/**
 * Base interface for a source of context events (e.g., time, location, app state).
 *
 * Implementations are registered in [ContextSourceRegistry] and emit events
 * whenever their match state changes or a one-shot event pulse arrives.
 */
interface ContextSource {
    val type: String
    fun events(app: Context): Flow<ContextEvent>
}

/**
 * A source that can prove its upstream callbacks are subscribed before an external producer starts.
 * The production event source uses this handshake for non-replayed pulse events; diagnostic
 * collectors use [events] and never acquire or retain producer ownership.
 */
interface SubscriptionReadyContextSource : ContextSource {
    fun events(app: Context, onSubscribed: () -> Unit): Flow<ContextEvent>

    override fun events(app: Context): Flow<ContextEvent> = events(app) {}
}

/**
 * A pulse source whose upstream work can be narrowed to the event requested by one matcher.
 *
 * This keeps shared producers demand-gated: a calendar-only profile must not make a sun ticker
 * wake up, and an NFC-only profile must not make CalendarProvider queries.
 */
interface EventDemandContextSource : SubscriptionReadyContextSource {
    fun events(
        app: Context,
        requestedEvent: String?,
        onSubscribed: () -> Unit,
    ): Flow<ContextEvent>

    override fun events(app: Context): Flow<ContextEvent> =
        events(app, requestedEvent = null, onSubscribed = {})

    override fun events(app: Context, onSubscribed: () -> Unit): Flow<ContextEvent> =
        events(app, requestedEvent = null, onSubscribed = onSubscribed)
}

/**
 * A level source whose upstream work can be narrowed to the state key requested by one matcher.
 *
 * The Inspector calls the regular [events] overload with no key so it can show the complete live
 * state snapshot. Production matchers pass their predicate key and avoid starting unrelated
 * sensors, GPS, or telephony callbacks.
 */
interface StateDemandContextSource : ContextSource {
    fun events(app: Context, requestedStateKey: String?): Flow<ContextEvent>

    override fun events(app: Context): Flow<ContextEvent> =
        events(app, requestedStateKey = null)
}

object ContextSourceRegistry {
    private val byType = Collections.synchronizedMap(mutableMapOf<String, ContextSource>())

    fun register(source: ContextSource) { byType[source.type] = source }
    fun get(type: String): ContextSource? = byType[type]
    fun all(): Collection<ContextSource> = byType.values.toList() // Return a copy to prevent external modification
}
