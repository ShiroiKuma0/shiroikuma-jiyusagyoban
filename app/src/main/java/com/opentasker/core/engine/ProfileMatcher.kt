package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Watches a Profile's contexts and emits state transitions (false -> true = activate, true -> false = deactivate).
 */
class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
    private val db: AppDatabase,
) {
    private var lastMatched = false

    fun stateChanges(): Flow<ProfileStateChange> {
        if (profile.contexts.isEmpty()) {
            return emptyFlow()
        }

        val flows = profile.contexts.map { spec ->
            val source = ContextSourceRegistry.get(spec.type.name.lowercase())
                ?: return emptyFlow()
            source.events(app).map { event ->
                if (spec.invert) !event.matched else event.matched
            }
        }

        return combine(flows) { allMatches ->
            allMatches.all { it }
        }
            .distinctUntilChanged()
            .map { nowMatched ->
                val prev = lastMatched
                lastMatched = nowMatched
                if (prev == nowMatched) null else {
                    if (nowMatched) ProfileStateChange.Activated else ProfileStateChange.Deactivated
                }
            }
            .filterNotNull()
    }

    private fun emptyFlow(): Flow<Nothing> = kotlinx.coroutines.flow.emptyFlow()
}

sealed class ProfileStateChange {
    data object Activated : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}

private fun <T> Sequence<T>.filterNotNull(): Flow<T> where T : Any =
    this.asFlow().filter { it != null }

private suspend inline fun <T> Sequence<T>.asFlow(): Flow<T> =
    kotlinx.coroutines.flow.flowOf(*toList().toTypedArray())

private inline fun <T> Flow<T?>.filter(crossinline predicate: suspend (T) -> Boolean): Flow<T> =
    this.mapNotNull { if (predicate(it)) it else null }
