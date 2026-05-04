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
    private var lastMatched = false

    fun stateChanges(): Flow<ProfileStateChange?> {
        if (profile.contexts.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(null)
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
            kotlinx.coroutines.flow.flowOf(null)
        } else {
            combine(flows) { allMatches ->
                allMatches.all { it }
            }
                .distinctUntilChanged()
                .map { nowMatched ->
                    val prev = lastMatched
                    lastMatched = nowMatched
                    if (prev == nowMatched) {
                        null
                    } else {
                        if (nowMatched) ProfileStateChange.Activated else ProfileStateChange.Deactivated
                    }
                }
                .filterNotNull()
        }
    }
}

sealed class ProfileStateChange {
    data object Activated : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}
