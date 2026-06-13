package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.contexts.ContextMatchEvaluator
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan

/**
 * Watches a Profile's contexts and emits level-state transitions or event pulses.
 * Level contexts activate/deactivate when the aggregate match changes; event
 * contexts activate on each matching pulse.
 * 
 * Includes performance monitoring to detect slow matchers.
 */
class ProfileMatcher(
    private val app: Context,
    private val profile: Profile,
) {
    private val tag = "ProfileMatcher[${profile.name}]"
    private val performanceThresholdMs = 1000L // Warn if evaluation takes > 1 second
    private val locationDwellStateStore = LocationDwellStateStore(app)
    
    fun stateChanges(): Flow<ProfileStateChange> {
        if (profile.contexts.isEmpty()) {
            return emptyFlow()
        }

        val hasPulseContexts = profile.contexts.any { it.type == ContextType.EVENT }
        val flows = profile.contexts.mapIndexed { index, spec ->
            val sourceType = ContextMatchEvaluator.sourceKey(spec.type)
            val source = sourceType?.let(ContextSourceRegistry::get)
            if (source != null) {
                val isPulseContext = spec.type == ContextType.EVENT
                source.events(app).scan(ContextMatchUpdate.initial(isPulseContext)) { previous, event ->
                    val preparedEvent = if (spec.type == ContextType.LOCATION) {
                        locationDwellStateStore.enrich(profile.id, index, spec, event)
                    } else {
                        event
                    }
                    val matched = ContextMatchEvaluator.matches(spec, preparedEvent)
                    val effectiveMatched = if (spec.invert) !matched else matched
                    ContextMatchUpdate(
                        matched = effectiveMatched,
                        pulseContext = isPulseContext,
                        pulseSequence = if (isPulseContext) previous.pulseSequence + 1 else 0,
                    )
                }
            } else {
                AppLogger.warn(tag, "No context source registered for ${spec.type}; treating as non-matching")
                flowOf(ContextMatchUpdate.initial(spec.type == ContextType.EVENT))
            }
        }

        return if (flows.isEmpty()) {
            emptyFlow()
        } else {
            combine(flows) { allMatches ->
                evaluateSnapshot(allMatches)
            }.let { snapshots ->
                profileStateChangesFromSnapshots(snapshots, hasPulseContexts) { change ->
                    val startTime = System.currentTimeMillis()
                    when (change) {
                        ProfileStateChange.Activated -> {
                            val reason = if (hasPulseContexts) "Profile activated by event pulse" else "Profile activated"
                            AppLogger.info(tag, reason)
                        }
                        ProfileStateChange.Deactivated -> AppLogger.info(tag, "Profile deactivated")
                    }
                    val duration = System.currentTimeMillis() - startTime
                    AppLogger.debug(tag, "State transition evaluated in ${duration}ms")
                }
            }
        }
    }

    private fun evaluateSnapshot(
        contextMatches: Array<ContextMatchUpdate>,
    ): ProfileMatchSnapshot {
        val startTime = System.currentTimeMillis()
        val allMatched = evaluateWithOrGroups(contextMatches, profile.contexts)
        val pulseSequence = contextMatches
            .filter { it.pulseContext }
            .sumOf { it.pulseSequence }
        val duration = System.currentTimeMillis() - startTime

        if (duration > performanceThresholdMs) {
            AppLogger.warn(tag, "Slow profile evaluation: ${duration}ms (threshold: ${performanceThresholdMs}ms)")
        }

        return ProfileMatchSnapshot(
            allMatched = allMatched,
            pulseSequence = pulseSequence,
        )
    }

}

internal data class ContextMatchUpdate(
    val matched: Boolean,
    val pulseContext: Boolean,
    val pulseSequence: Long,
) {
    companion object {
        fun initial(pulseContext: Boolean): ContextMatchUpdate =
            ContextMatchUpdate(matched = false, pulseContext = pulseContext, pulseSequence = 0)
    }
}

internal data class ProfileMatchSnapshot(
    val allMatched: Boolean,
    val pulseSequence: Long,
)

private data class PulseAccumulator(
    val lastPulseSequence: Long,
    val change: ProfileStateChange?,
)

internal fun profileStateChangesFromSnapshots(
    snapshots: Flow<ProfileMatchSnapshot>,
    hasPulseContexts: Boolean,
    onChange: (ProfileStateChange) -> Unit = {},
): Flow<ProfileStateChange> =
    if (hasPulseContexts) {
        snapshots.scan(PulseAccumulator(lastPulseSequence = 0, change = null)) { previous, snapshot ->
            val pulseChanged = snapshot.pulseSequence != previous.lastPulseSequence
            val change = if (pulseChanged && snapshot.pulseSequence > 0 && snapshot.allMatched) {
                ProfileStateChange.Activated
            } else {
                null
            }
            PulseAccumulator(lastPulseSequence = snapshot.pulseSequence, change = change)
        }.mapNotNull { accumulator ->
            accumulator.change?.also(onChange)
        }
    } else {
        snapshots.map { it.allMatched }
            .distinctUntilChanged()
            .scan(Pair(false, false)) { (_, prev), now -> Pair(prev, now) }
            .mapNotNull { (prev, now) ->
                val change = when {
                    !prev && now -> ProfileStateChange.Activated
                    prev && !now -> ProfileStateChange.Deactivated
                    else -> null
                }
                change?.also(onChange)
            }
    }

internal fun evaluateWithOrGroups(
    contextMatches: Array<ContextMatchUpdate>,
    specs: List<ContextSpec>,
): Boolean {
    if (contextMatches.isEmpty()) return false
    val andTerms = mutableListOf<Boolean>()
    val orGroups = mutableMapOf<String, Boolean>()

    for (i in contextMatches.indices) {
        val group = specs.getOrNull(i)?.orGroup
        if (group != null) {
            orGroups[group] = orGroups.getOrDefault(group, false) || contextMatches[i].matched
        } else {
            andTerms.add(contextMatches[i].matched)
        }
    }
    return andTerms.all { it } && orGroups.values.all { it }
}

sealed class ProfileStateChange {
    data object Activated : ProfileStateChange()
    data object Deactivated : ProfileStateChange()
}
