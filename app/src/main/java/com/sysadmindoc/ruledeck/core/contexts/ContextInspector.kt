package com.sysadmindoc.ruledeck.core.contexts

import com.sysadmindoc.ruledeck.core.engine.CausalLoopDiagnostic
import com.sysadmindoc.ruledeck.core.engine.ContextMatchUpdate
import com.sysadmindoc.ruledeck.core.engine.contextMatchUpdate
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextBooleanOperator
import com.sysadmindoc.ruledeck.core.model.ContextExpressionNode
import com.sysadmindoc.ruledeck.core.model.isValidForContextCount
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.ProfileLifecyclePolicy
import com.sysadmindoc.ruledeck.core.model.ProfileLifecycleStrings
import com.sysadmindoc.ruledeck.i18n.tr
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

/** Health of the latest observation, independent of whether the source itself needs setup. */
enum class ContextObservationStatus(val label: String) {
    Loading("Loading"),
    Ready("Ready"),
    Stale("Stale"),
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
    /**
     * Setup gaps that stop one kind of event while the source itself runs, keyed by the lowercase
     * `event` value they block, like "nfc" while Android withholds tags. Only those contexts show it.
     */
    val eventSetupGaps: Map<String, String> = emptyMap(),
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
    val causalLoop: CausalLoopDiagnostic? = null,
)

data class ProfileInspection(
    val profileId: Long,
    val profileName: String,
    val enabled: Boolean,
    val matching: Boolean,
    val summary: String,
    val contexts: List<ContextCheck>,
    val logicExplanation: String = "",
    val profile: Profile? = null,
    val priority: Int = 0,
    val suppressionReason: String? = null,
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
    /** The value can't be told yet, so a NOT over this context doesn't match either. */
    val valueUnknown: Boolean = false,
)

fun inspectProfiles(
    profiles: List<Profile>,
    sourceSnapshots: Collection<ContextSourceSnapshot>,
    nowMs: Long = System.currentTimeMillis(),
    lifecycleStrings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): List<ProfileInspection> {
    val sourcesByKey = sourceSnapshots.associateBy { it.key }
    val inspections = profiles
        .map { profile -> inspectProfile(profile, sourcesByKey, nowMs, lifecycleStrings, observationTransformer) }
        .sortedWith(compareBy<ProfileInspection> { !it.enabled }.thenBy { it.profileName.lowercase(Locale.US) })
    val profileById = profiles.associateBy(Profile::id)
    val matchingProfiles = inspections.mapNotNull { inspection ->
        inspection.profile
            ?.takeIf { inspection.matching }
    }
    return inspections.map { inspection ->
        val profile = profileById[inspection.profileId] ?: return@map inspection
        val suppression = ProfileLifecyclePolicy.suppressionByPriority(profile, matchingProfiles, lifecycleStrings)
        if (inspection.matching && suppression != null) {
            inspection.copy(matching = false, summary = suppression, suppressionReason = suppression)
        } else {
            inspection
        }
    }
}

fun inspectProfile(
    profile: Profile,
    sourcesByKey: Map<String, ContextSourceSnapshot>,
    nowMs: Long = System.currentTimeMillis(),
    lifecycleStrings: ProfileLifecycleStrings = ProfileLifecycleStrings.English,
    observationTransformer: ContextObservationTransformer = { _, _, _, observation -> observation },
): ProfileInspection {
    val checks = profile.contexts.mapIndexed { index, spec ->
        inspectContextForProfile(profile, index, spec, sourcesByKey, observationTransformer)
    }
    val contextsMatch = checks.isNotEmpty() && evaluateChecks(checks, profile.contextExpression)
    val lifecycleSuppression = ProfileLifecyclePolicy.suppressionReason(profile, nowMs, lifecycleStrings)
    val matching = profile.enabled && contextsMatch && lifecycleSuppression == null
    val summary = when {
        !profile.enabled -> tr("Profile is disabled.")
        lifecycleSuppression != null -> lifecycleSuppression
        checks.isEmpty() -> tr("No contexts are configured.")
        contextsMatch && checks.all { it.effectiveMatched } -> tr("All contexts currently match.")
        // An "any of" rule or an OR group holds while some of its contexts don't.
        contextsMatch -> tr("Enough contexts match for the profile's rule.")
        else -> blockingReason(checks, profile.contextExpression) ?: tr("The profile's context rule doesn't hold.")
    }

    return ProfileInspection(
        profileId = profile.id,
        profileName = profile.name,
        enabled = profile.enabled,
        matching = matching,
        summary = summary,
        contexts = checks,
        logicExplanation = profile.contextExpression?.let { explainContextExpression(it, checks) }.orEmpty(),
        profile = profile,
        priority = profile.priority,
        suppressionReason = lifecycleSuppression,
    )
}

/**
 * Mirrors the engine's evaluateWithOrGroups semantics: contexts sharing an orGroup
 * need only one member to match; ungrouped contexts are AND terms. The Inspector
 * must agree with the engine or its "does not match" explanations lie for profiles
 * that are genuinely active through an OR group.
 */
internal fun evaluateChecksWithOrGroups(checks: List<ContextCheck>): Boolean {
    val andTerms = mutableListOf<Boolean>()
    val orGroups = mutableMapOf<String, Boolean>()
    for (check in checks) {
        val group = check.spec.orGroup
        if (group != null) {
            orGroups[group] = orGroups.getOrDefault(group, false) || check.effectiveMatched
        } else {
            andTerms.add(check.effectiveMatched)
        }
    }
    return andTerms.all { it } && orGroups.values.all { it }
}

internal fun evaluateChecks(
    checks: List<ContextCheck>,
    expression: ContextExpressionNode?,
): Boolean = if (expression == null) {
    evaluateChecksWithOrGroups(checks)
} else if (!expression.isValidForContextCount(checks.size)) {
    false
} else {
    expression.evaluate(checks.map { if (it.valueUnknown) null else it.effectiveMatched }) == true
}

/**
 * Why a profile's contexts don't hold, from the context that fails its rule. Without a rule that's
 * the first context that doesn't match, leaving out one whose OR group another member already
 * satisfies and naming last one whose value isn't known yet or whose OR group has such a member,
 * since either might still come right. Those keep their order, an OR group standing where its first
 * member does with its known members ahead, the way the walk below ranks the same contexts written
 * as a rule. With one, the rule is walked down to a context whose state keeps it from holding, so
 * a context a NOT is happy to see off isn't blamed. Null when nothing can be named, such as a rule
 * that can't be evaluated.
 */
internal fun blockingReason(checks: List<ContextCheck>, expression: ContextExpressionNode?): String? {
    if (expression == null) {
        val satisfiedGroups = checks.filter { it.effectiveMatched }.mapNotNullTo(HashSet()) { it.spec.orGroup }
        val unsureGroups = checks.filter { it.valueUnknown }.mapNotNullTo(HashSet()) { it.spec.orGroup }
        val failing = checks.filter { !it.effectiveMatched && it.spec.orGroup !in satisfiedGroups }
        val groupStart = HashMap<String, Int>()
        failing.forEachIndexed { index, check -> check.spec.orGroup?.let { groupStart.putIfAbsent(it, index) } }
        return failing.withIndex().minWithOrNull(
            compareBy<IndexedValue<ContextCheck>>(
                { it.value.valueUnknown || it.value.spec.orGroup in unsureGroups },
                { it.value.spec.orGroup?.let(groupStart::getValue) ?: it.index },
                { it.value.valueUnknown },
            ),
        )?.value?.reason
    }
    if (!expression.isValidForContextCount(checks.size)) return null
    val values = checks.map { if (it.valueUnknown) null else it.effectiveMatched }
    // The leaf keeping [node] from being [wanted], and whether that leaf needs to match.
    fun culprit(node: ContextExpressionNode, wanted: Boolean): Pair<ContextCheck, Boolean>? {
        if (node.evaluate(values) == wanted) return null
        val inner = if (node.invert) !wanted else wanted
        if (node.isLeaf()) return node.contextIndex?.let(checks::getOrNull)?.let { it to inner }
        // A child that is surely the wrong way goes before one whose value isn't known yet, which
        // might turn out right and still leave the rule failing.
        val (sure, unsure) = node.children.partition { it.evaluate(values) == !inner }
        return (sure + unsure).firstNotNullOfOrNull { culprit(it, inner) }
    }
    val (check, needsMatch) = culprit(expression, true) ?: return null
    return when {
        needsMatch || check.valueUnknown -> check.reason
        else -> tr("Context %1\$s matches, and the rule needs it not to.", check.index + 1)
    }
}

internal fun explainContextExpression(
    expression: ContextExpressionNode,
    checks: List<ContextCheck>,
): String {
    fun explain(node: ContextExpressionNode): String {
        // Bound locally: `operator` is a public property of another module now, so checking it for
        // null inside the `when` subject no longer smart-casts it.
        val nodeOperator = node.operator
        return when {
            node.isLeaf() -> {
                val check = node.contextIndex?.let(checks::getOrNull)
                val number = (node.contextIndex ?: -1) + 1
                val leaf = when {
                    check?.effectiveMatched == true -> tr("Context %1\$s matches", number)
                    check?.valueUnknown == true -> tr("Context %1\$s is unknown", number)
                    else -> tr("Context %1\$s doesn't match", number)
                }
                if (node.invert) tr("not (%1\$s)", leaf) else leaf
            }
            nodeOperator != null && node.children.isNotEmpty() -> {
                val body = node.children.joinToString(", ", transform = ::explain)
                when (nodeOperator) {
                    ContextBooleanOperator.AND -> if (node.invert) tr("not all of (%1\$s)", body) else tr("all of (%1\$s)", body)
                    ContextBooleanOperator.OR -> if (node.invert) tr("not any of (%1\$s)", body) else tr("any of (%1\$s)", body)
                }
            }
            else -> tr("Invalid context group")
        }
    }
    // "All of (Context 1 matches, Context 2 doesn't match)", in words rather than ALL(Context 1=match).
    return explain(expression).replaceFirstChar { it.uppercaseChar() }
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
    val observation = snapshot?.takeIf(ContextSourceSnapshot::registered)?.lastObservation
        ?.let { observationTransformer(profile, index, spec, it) }
        // The engine skips a plugin result meant for another plugin or setting, so it isn't a reading here.
        ?.takeUnless { spec.type == ContextType.PLUGIN && !ContextMatchEvaluator.pluginEventAddressesSpec(spec, it.event) }
    val rawMatched = observation?.let { ContextMatchEvaluator.matches(spec, it.event) } ?: false
    val sourceStatus = snapshot?.status ?: ContextSourceStatus.Missing
    // The engine's own rule, so this verdict is the one the profile gets: a state with no reading
    // yet, or one whose value is unknown, is unknown, so neither its inverted context nor a NOT
    // group over it turns on. A source that stopped or lost its setup keeps counting its last
    // value there, so it does here too, and the reason says so.
    val update = observation?.let { contextMatchUpdate(spec, it.event) }
        ?: ContextMatchUpdate.initial(pulseContext = spec.type == ContextType.EVENT)
    val valueUnknown = update.unknown
    val effectiveMatched = update.matched
    val reason = contextReason(spec, sourceKey, snapshot, observation, rawMatched, effectiveMatched)

    return ContextCheck(
        index = index,
        spec = spec,
        sourceKey = sourceKey,
        sourceLabel = snapshot?.label ?: sourceKey?.toContextSourceLabel() ?: tr("Unknown source"),
        sourceStatus = sourceStatus,
        rawMatched = rawMatched,
        effectiveMatched = effectiveMatched,
        lastObservation = observation,
        reason = reason,
        configSummary = contextConfigSummary(spec),
        valueUnknown = valueUnknown,
    )
}

fun contextConfigSummary(spec: ContextSpec): String {
    val summary = when (spec.type) {
        ContextType.PLUGIN -> {
            val pkg = spec.config["package"].orEmpty().ifBlank { tr("none") }
            val blurb = spec.config["blurb"]?.takeIf { it.isNotBlank() }
            if (blurb != null) "$pkg ($blurb)" else pkg
        }
        else -> readableContextSummary(spec) ?: configEntriesSummary(spec.config)
    }
    return if (spec.invert) tr("%1\$s; inverted", summary) else summary
}

/**
 * The plain-words line for a day, state or sun context, or null for any other. [entries] writes the
 * fields the line doesn't cover and [quote] each part of a state the line quotes (its name, operator
 * and value), so Flow can mask and shorten them the way it shows every other value.
 */
internal fun readableContextSummary(
    spec: ContextSpec,
    entries: (Map<String, String>) -> String = ::configEntriesSummary,
    quote: (String) -> String = { it.trim() },
): String? = when (spec.type) {
    ContextType.DAY -> DaySchedule.displayLabel(spec.config["days"] ?: spec.config["day"].orEmpty())
    ContextType.STATE -> stateConditionSummary(spec, entries, quote)
    ContextType.EVENT -> sunEventSummary(spec, entries)
    else -> null
}

/** Names the moment a sun context waits for, "Civil dusk" rather than "elevation=civil, event=sunset". */
private fun sunEventSummary(spec: ContextSpec, entries: (Map<String, String>) -> String): String? {
    val event = spec.config["event"]?.trim()?.lowercase()
    if (event != "sunrise" && event != "sunset") return null
    val raw = spec.config["elevation"]?.trim().orEmpty()
    // An unreadable elevation never matches, so it shows as written.
    val degrees = SunEventCalculator.elevationDegrees(raw) ?: return null
    val rising = event == "sunrise"
    val moment = when {
        raw.isEmpty() -> if (rising) tr("Sunrise") else tr("Sunset")
        raw.lowercase() in SunEventCalculator.NAMED_ELEVATIONS -> {
            val name = raw.lowercase().replaceFirstChar { it.uppercase() }
            if (rising) {
                tr("%1\$s dawn (sun %2\$s degrees below the horizon)", tr(name), degreesText(-degrees))
            } else {
                tr("%1\$s dusk (sun %2\$s degrees below the horizon)", tr(name), degreesText(-degrees))
            }
        }
        else -> {
            when {
                degrees > 0 && rising -> tr("Sun rising past %1\$s degrees above the horizon", degreesText(degrees))
                degrees > 0 -> tr("Sun setting past %1\$s degrees above the horizon", degreesText(degrees))
                degrees < 0 && rising -> tr("Sun rising past %1\$s degrees below the horizon", degreesText(-degrees))
                degrees < 0 -> tr("Sun setting past %1\$s degrees below the horizon", degreesText(-degrees))
                rising -> tr("Sun rising past the horizon")
                else -> tr("Sun setting past the horizon")
            }
        }
    }
    val extras = spec.config.filterKeys { it !in SUN_EVENT_KEYS }
    return if (extras.isEmpty()) moment else "$moment, ${entries(extras)}"
}

private val SUN_EVENT_KEYS = setOf("event", "elevation")

private fun degreesText(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else value.toString()

/**
 * "battery_level" and "connectedCount" read as "Battery level" and "Connected count", so raw keys
 * don't show as code. An acronym keeps its capitals, so "wifiSSID" reads as "Wifi SSID".
 */
internal fun readableFieldName(key: String): String {
    // An all-caps key like SCREEN_ON is a constant's spelling, not a run of acronyms.
    val keepAcronyms = key.any(Char::isLowerCase) || '_' !in key
    return key.replace(CAMEL_HUMP, "\$1 \$2").replace(ACRONYM_END, "\$1 \$2").replace('_', ' ')
        .split(' ')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word ->
            if (keepAcronyms && word.length > 1 && word.none(Char::isLowerCase)) word else word.lowercase(Locale.ROOT)
        }
        .replaceFirstChar { it.uppercaseChar() }
}

private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")
private val ACRONYM_END = Regex("([A-Z]+)([A-Z][a-z])")

private fun configEntriesSummary(config: Map<String, String>): String =
    config.entries
        .sortedBy { it.key }
        .joinToString { "${readableFieldName(it.key)}: ${it.value}" }
        .ifBlank { tr("No configuration") }

/** Reads as the check it makes, "Bluetooth device nearby = Car" rather than "key=bt_near, operator==, value=Car". */
private fun stateConditionSummary(
    spec: ContextSpec,
    entries: (Map<String, String>) -> String,
    quote: (String) -> String,
): String? {
    // The engine reads a non-blank predicate alone, so one it can't parse shows as written, not as key fields it ignores.
    val predicate = spec.config["predicate"]
    if (!predicate.isNullOrBlank() && parseStatePredicate(predicate) == null) return null
    val (key, operator, expected) = stateCondition(spec)
    val normalized = normalizeStateKey(key).ifEmpty { return null }
    val label = if (normalized == BT_NEAR_STATE_KEY) tr("Bluetooth device nearby") else quote(normalized.replace('_', ' '))
    val condition = "$label ${quote(operator.trim().ifEmpty { "=" })} ${quote(expected)}".trim()
    if (spec.config.keys.all { it in STATE_CONDITION_KEYS }) return condition
    return "$condition, ${entries(spec.config.filterKeys { it !in STATE_CONDITION_KEYS })}"
}

private val STATE_CONDITION_KEYS = setOf("predicate", "key", "operator", "value")

fun String.toContextSourceLabel(): String = when (this) {
    "app" -> tr("Application")
    "time" -> tr("Time and day")
    "state" -> tr("Device state")
    "event" -> tr("System event")
    "location" -> tr("Location")
    "plugin" -> tr("Plugin condition")
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
    if (sourceKey == null) return tr("This context type is not mapped to a runtime source.")
    if (snapshot == null || !snapshot.registered) return tr("No registered runtime source for %1\$s.", sourceKey.toContextSourceLabel())
    val lastValueNote = if (observation != null) tr(" Its last value still counts until it reports again.") else ""
    snapshot.error?.let { return tr("The %1\$s source stopped with an error: %2\$s", tr(snapshot.label), it) + lastValueNote }
    if (!snapshot.setupReady) {
        return (snapshot.setupDetail?.let { tr(it) } ?: tr("%1\$s needs setup before it can report values.", tr(snapshot.label))) + lastValueNote
    }
    if (spec.type == ContextType.EVENT) {
        spec.config["event"]?.lowercase(Locale.ROOT)?.let(snapshot.eventSetupGaps::get)?.let { return tr(it) }
    }
    if (observation == null && spec.type == ContextType.PLUGIN && snapshot.lastObservation != null) {
        return tr("Waiting for this plugin's first result. The latest one was for another plugin or setting.")
    }
    if (observation == null) return tr("Waiting for the first %1\$s event.", tr(snapshot.label))
    if (observation.event.type != sourceKey) return tr("Latest event came from %1\$s, not %2\$s.", observation.event.type, sourceKey)
    if (spec.type == ContextType.STATE) {
        stateSetupMarkerKey(spec)
            ?.let { observation.event.metadata[it] }
            ?.takeIf(String::isNotBlank)
            ?.let { return tr(it) }
    }
    timeBoundsGap(spec, observation.event.metadata)?.let { return it }
    if (spec.type == ContextType.EVENT) {
        return when {
            spec.invert && effectiveMatched -> tr("Latest event does not satisfy the configuration, so the inverted event context can trigger on this pulse.")
            spec.invert && rawMatched -> tr("Latest event satisfies the configuration, so the inverted event context blocks this pulse.")
            effectiveMatched -> tr("Latest event satisfies the configuration; event contexts are one-shot pulses and can trigger again on each matching event.")
            else -> tr("Latest event does not satisfy the configuration; event contexts wait for the next matching pulse.")
        }
    }

    val verdict = when {
        spec.invert && effectiveMatched -> tr("Latest value does not satisfy the configuration, so the inverted context matches.")
        spec.invert && rawMatched -> tr("Latest value satisfies the configuration, so the inverted context blocks the profile.")
        effectiveMatched -> tr("Latest value satisfies the configuration.")
        else -> tr("Latest value does not satisfy the configuration.")
    }
    return verdict + timeBoundsRead(spec, observation.event.metadata)
}

/** Why a Time context bounded by a variable can't match yet, naming the variable, or null when every bound holds a time. */
private fun timeBoundsGap(spec: ContextSpec, metadata: Map<String, String>): String? {
    val unresolved = TimeContextBounds.unresolvedBounds(spec, metadata).ifEmpty { return null }
    unresolved.firstOrNull { TimeContextBounds.variableName(it) == null }?.let { unreadable ->
        if (!TimeContextBounds.isLocalVariable(unreadable)) {
            return tr("%1\$s isn't a variable name, so this context stays off. A name starts with a letter and has only letters, digits, _ and -, like %%Sunrise.", unreadable)
        }
        return tr("%1\$s is a local variable, which a context can't read, so this context stays off. A global has a capital letter in its name.", unreadable)
    }
    return if (unresolved.size == 1) {
        tr("%1\$s doesn't hold a time yet, so this context stays off, inverted or not.", unresolved.first())
    } else {
        tr("%1\$s don't hold a time yet, so this context stays off, inverted or not.", unresolved.joinToString(tr(" and ")))
    }
}

/** " Read %Sunrise as 06:42 and %Sunset as 16:51." for a Time context bounded by variables, else empty. */
private fun timeBoundsRead(spec: ContextSpec, metadata: Map<String, String>): String {
    val read = TimeContextBounds.boundTimes(spec, metadata).ifEmpty { return "" }
    return tr(" Read %1\$s.", read.joinToString(tr(" and ")) { (name, time) -> tr("%1\$s as %2\$s", name, time) })
}

fun ContextSourceSnapshot.observationStatus(
    nowMs: Long,
    staleAfterMs: Long = CONTEXT_OBSERVATION_STALE_AFTER_MS,
): ContextObservationStatus = when {
    error != null -> ContextObservationStatus.Error
    lastObservation == null -> ContextObservationStatus.Loading
    observationAgeMs(nowMs)?.let { it > staleAfterMs } == true -> ContextObservationStatus.Stale
    else -> ContextObservationStatus.Ready
}

fun ContextSourceSnapshot.observationAgeMs(nowMs: Long): Long? =
    lastObservation?.let { (nowMs - it.observedAtMs).coerceAtLeast(0L) }

internal const val CONTEXT_OBSERVATION_STALE_AFTER_MS = 5 * 60_000L
