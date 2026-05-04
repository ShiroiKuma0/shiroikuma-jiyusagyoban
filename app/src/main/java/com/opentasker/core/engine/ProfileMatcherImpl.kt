package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.logging.AppLogger
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
 * 
 * Includes performance monitoring to detect slow matchers.
 */
class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
) {
    private val tag = "ProfileMatcher[${profile.name}]"
    private val performanceThresholdMs = 1000L // Warn if evaluation takes > 1 second
    
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
                val startTime = System.currentTimeMillis()
                val result = allMatches.all { it }
                val duration = System.currentTimeMillis() - startTime
                
                // Log performance if threshold exceeded
                if (duration > performanceThresholdMs) {
                    AppLogger.warn(tag, "Slow profile evaluation: ${duration}ms (threshold: ${performanceThresholdMs}ms)")
                }
                
                result
            }
                .distinctUntilChanged()
                .scan(Pair(false, false)) { (_, prev), now -> Pair(prev, now) }
                .mapNotNull { (prev, now) ->
                    val startTime = System.currentTimeMillis()
                    val result = when {
                        !prev && now -> {
                            AppLogger.info(tag, "Profile activated")
                            ProfileStateChange.Activated
                        }
                        prev && !now -> {
                            AppLogger.info(tag, "Profile deactivated")
                            ProfileStateChange.Deactivated
                        }
                        else -> null
                    }
                    val duration = System.currentTimeMillis() - startTime
                    AppLogger.debug(tag, "State transition evaluated in ${duration}ms")
                    result
                }
        }
    }
}

sealed class ProfileStateChange {
    data object Activated : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}
