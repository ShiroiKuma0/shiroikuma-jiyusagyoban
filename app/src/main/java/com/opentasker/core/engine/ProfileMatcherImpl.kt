package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull

/**
 * Watches a Profile's contexts and emits state transitions.
 * When all contexts match, profile is "active". On any context change, re-evaluates.
 */
class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
) {
    fun stateChanges(): Flow<ProfileStateChange> {
        if (profile.contexts.isEmpty()) {
            return kotlinx.coroutines.flow.emptyFlow()
        }

        val flows = profile.contexts.mapNotNull { spec ->
            val source = ContextSourceRegistry.get(spec.type.name.lowercase())
            if (source != null) {
                source.events(app).map { event ->
                    val matched = event.matched
                    if (spec.invert) !matched else matched
                }
            } else {
                null
            }
        }

        return if (flows.isEmpty()) {
            kotlinx.coroutines.flow.emptyFlow()
        } else {
            combine(flows) { allMatches ->
                allMatches.all { it }
            }
                .distinctUntilChanged()
                .scan(Pair(false, false)) { (_, prev), now -> Pair(prev, now) }
                .mapNotNull { (prev, now) ->
                    when {
                        !prev && now -> ProfileStateChange.Activated
                        prev && !now -> ProfileStateChange.Deactivated
                        else -> null
                    }
                }
        }
    }
}

sealed class ProfileStateChange {
    data object Activated : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}
