package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import java.util.Locale

typealias ContextObservationTransformer = (
    profile: Profile,
    contextIndex: Int,
    spec: ContextSpec,
    observation: ContextEventObservation,
) -> ContextEventObservation

enum class ContextSourceStatus(val label: String) {
    Active("Active"),
    Waiting("Waiting"),
    NeedsSetup("Needs setup"),
    Missing("Missing"),
    Error("Error"),
}

data class ContextEventObservation(
    val event: ContextEvent,
    val observedAtMs: Long,
)

data class ContextSourceSnapshot(
    val key: String,
    val label: String,
    val registered: Boolean,
    val setupReady: Boolean = true,
    val setupDetail: String? = null,
    val error: String? = null,
    val lastObservation: ContextEventObservation? = null,
) {
    val status: ContextSourceStatus
        get() = when {
            !registered -> ContextSourceStatus.Missing
            error != null -> ContextSourceStatus.Error
            !setupReady -> ContextSourceStatus.NeedsSetup
            lastObservation == null -> ContextSourceStatus.Waiting
            else -> ContextSourceStatus.Active
        }
}

data class ContextInspectionSnapshot(
    val generatedAtMs: Long,
    val sources: List<ContextSourceSnapshot>,
    val profiles: List<ProfileInspection>,
)

data class ProfileInspection(
    val profileId: Long,
    val profileName: String,
    val enabled: Boolean,
    val matching: Boolean,
    val summary: String,
    val contexts: List<ContextCheck>,
)

data class ContextCheck(
    val index: Int,
    val spec: ContextSpec,
    val sourceKey: String?,
    val sourceLabel: String,
    val sourceStatus: ContextSourceStatus,
    val rawMatched: Boolean,
    val effectiveMatched: Boolean,
    val lastObservation: ContextEventObservation?,
    val reason: String,
    val configSummary: String,
)

fun inspectProfiles(
    profiles: List<Profile>,
    sourceSnapshots: Collection<ContextSourceSnapshot>,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): List<ProfileInspection> {
    val sourcesByKey = sourceSnapshots.associateBy { it.key }
    return profiles
        .map { profile -> inspectProfile(profile, sourcesByKey, observationTransformer) }
        .sortedWith(compareBy<ProfileInspection> { !it.enabled }.thenBy { it.profileName.lowercase(Locale.US) })
}

fun inspectProfile(
    profile: Profile,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): ProfileInspection {
    val checks = profile.contexts.mapIndexed { index, spec ->
        inspectContextForProfile(profile, index, spec, sourcesByKey, observationTransformer)
    }
    val contextsMatch = checks.isNotEmpty() && checks.all { it.effectiveMatched }
    val matching = profile.enabled && contextsMatch
    val summary = when {
        !profile.enabled -> "Profile is disabled."
        checks.isEmpty() -> "No contexts are configured."
        contextsMatch -> "All contexts currently match."
        else -> checks.firstOrNull { !it.effectiveMatched }?.reason ?: "At least one context does not match."
    }

    return ProfileInspection(
        profileId = profile.id,
        profileName = profile.name,
        enabled = profile.enabled,
        matching = matching,
        summary = summary,
        contexts = checks,
    )
}

fun inspectContext(
    index: Int,
    spec: ContextSpec,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
): ContextCheck {
    return inspectContextForProfile(
        profile = Profile(id = 0, name = "Inspector", enterTaskId = 0),
        index = index,
        spec = spec,
        sourcesByKey = sourcesByKey,
        observationTransformer = { _, _, _, observation -> observation },
    )
}

private fun inspectContextForProfile(
    profile: Profile,
    index: Int,
    spec: ContextSpec,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
    observationTransformer: ContextObservationTransformer,
): ContextCheck {
    val sourceKey = ContextMatchEvaluator.sourceKey(spec.type)
    val snapshot = sourceKey?.let(sourcesByKey::get)
    val observation = snapshot?.lastObservation?.let {
        observationTransformer(profile, index, spec, it)
    }
    val rawMatched = observation?.let { ContextMatchEvaluator.matches(spec, it.event) } ?: false
    val sourceStatus = snapshot?.status ?: ContextSourceStatus.Missing
    val sourceCanMatch = sourceStatus == ContextSourceStatus.Active
    val effectiveMatched = sourceCanMatch && if (spec.invert) !rawMatched && observation != null else rawMatched
    val reason = contextReason(spec, sourceKey, snapshot, observation, rawMatched, effectiveMatched)

    return ContextCheck(
        index = index,
        spec = spec,
        sourceKey = sourceKey,
        sourceLabel = snapshot?.label ?: sourceKey?.toContextSourceLabel() ?: "Unknown source",
        sourceStatus = sourceStatus,
        rawMatched = rawMatched,
        effectiveMatched = effectiveMatched,
        lastObservation = observation,
        reason = reason,
        configSummary = contextConfigSummary(spec),
    )
}

fun contextConfigSummary(spec: ContextSpec): String {
    val summary = spec.config.entries
        .sortedBy { it.key }
        .joinToString { "${it.key}=${it.value}" }
        .ifBlank { "No configuration" }
    return if (spec.invert) "$summary; inverted" else summary
}

fun String.toContextSourceLabel(): String = when (this) {
    "app" -> "Application"
    "time" -> "Time and day"
    "state" -> "Device state"
    "event" -> "System event"
    "location" -> "Location"
    else -> replaceFirstChar { it.titlecase(Locale.US) }
}

private fun contextReason(
    spec: ContextSpec,
    sourceKey: String?,
    snapshot: ContextSourceSnapshot?,
    observation: ContextEventObservation?,
    rawMatched: Boolean,
    effectiveMatched: Boolean,
): String {
    if (sourceKey == null) return "This context type is not mapped to a runtime source."
    if (snapshot == null || !snapshot.registered) return "No registered runtime source for ${sourceKey.toContextSourceLabel()}."
    snapshot.error?.let { return "The ${snapshot.label} source stopped with an error: $it" }
    if (!snapshot.setupReady) {
        return snapshot.setupDetail ?: "${snapshot.label} needs setup before it can report values."
    }
    if (observation == null) return "Waiting for the first ${snapshot.label} event."
    if (observation.event.type != sourceKey) return "Latest event came from ${observation.event.type}, not $sourceKey."

    return when {
        spec.invert && effectiveMatched -> "Latest value does not satisfy the configuration, so the inverted context matches."
        spec.invert && rawMatched -> "Latest value satisfies the configuration, so the inverted context blocks the profile."
        effectiveMatched -> "Latest value satisfies the configuration."
        else -> "Latest value does not satisfy the configuration."
    }
}
