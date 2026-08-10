package com.opentasker.core.capabilities

import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.location.FossGeofenceEvaluator
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import java.util.Locale

enum class AutomationLintSeverity {
    WARNING,
    BLOCKING,
}

enum class AutomationLintCode {
    MISSING_REVERSAL,
    REPEATED_TRIGGERING,
    PRIORITY_CONFLICT,
    INTER_PROFILE_LOOP,
}

data class AutomationLintFinding(
    val code: AutomationLintCode,
    val severity: AutomationLintSeverity,
    val profileIds: List<Long>,
    val profileNames: List<String>,
    val title: String,
    val detail: String,
    val suggestedFix: String,
)

data class AutomationLintReport(
    val findings: List<AutomationLintFinding> = emptyList(),
) {
    val blockingFindings: List<AutomationLintFinding>
        get() = findings.filter { it.severity == AutomationLintSeverity.BLOCKING }

    val warnings: List<AutomationLintFinding>
        get() = findings.filter { it.severity == AutomationLintSeverity.WARNING }

    fun forProfile(profileId: Long): List<AutomationLintFinding> = findings.filter { profileId in it.profileIds }

    fun blockingFor(profileId: Long): List<AutomationLintFinding> =
        blockingFindings.filter { profileId in it.profileIds }
}

/**
 * High-confidence structural lint for trigger/action combinations.
 *
 * The analyzer deliberately proves only contradictions and direct task edges. It does not guess
 * about arbitrary device state, dynamic task references, or side effects hidden behind plugins;
 * uncertain cases remain warnings or are omitted rather than becoming noisy blockers.
 */
object AutomationLint {
    fun analyze(profile: Profile, tasks: List<Task>, otherProfiles: List<Profile> = emptyList()): AutomationLintReport =
        analyze((otherProfiles.filterNot { it.id == profile.id } + profile), tasks)

    fun analyze(profiles: List<Profile>, tasks: List<Task>): AutomationLintReport {
        if (profiles.isEmpty()) return AutomationLintReport()
        val findings = linkedSetOf<AutomationLintFinding>()
        val taskById = tasks.associateBy(Task::id)

        profiles.forEach { profile ->
            val enterTask = taskById[profile.enterTaskId]
            val writes = settingWrites(profile, tasks)
            val unreversed = writes.filterNot(SettingWrite::automaticallyReversed)
            if (profile.exitTaskId == null && unreversed.isNotEmpty()) {
                findings += AutomationLintFinding(
                    code = AutomationLintCode.MISSING_REVERSAL,
                    severity = AutomationLintSeverity.WARNING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = "Missing reversal",
                    detail = "${profile.name} writes ${unreversed.joinToString { it.key }} when it enters but has no exit task.",
                    suggestedFix = "Add an exit task that restores the setting, or use a bounded temporary-state action.",
                )
            }

            if (profile.contexts.any { it.type == ContextType.STATE } && !hasRetriggerGuard(profile, enterTask)) {
                findings += AutomationLintFinding(
                    code = AutomationLintCode.REPEATED_TRIGGERING,
                    severity = AutomationLintSeverity.WARNING,
                    profileIds = listOf(profile.id),
                    profileNames = listOf(profile.name),
                    title = "Repeated triggering",
                    detail = "${profile.name} has a state context without a cooldown, dwell, or explicit idempotency guard.",
                    suggestedFix = "Add a cooldown, a dwell requirement, or an explicit guard condition to the enter task.",
                )
            }
        }

        val enabledProfiles = profiles.filter(Profile::enabled)
        for (leftIndex in enabledProfiles.indices) {
            for (rightIndex in leftIndex + 1 until enabledProfiles.size) {
                val left = enabledProfiles[leftIndex]
                val right = enabledProfiles[rightIndex]
                if (!contextsMayOverlap(left, right)) continue
                val leftWrites = settingWrites(left, tasks).mapTo(hashSetOf(), SettingWrite::key)
                val rightWrites = settingWrites(right, tasks).mapTo(hashSetOf(), SettingWrite::key)
                val overlap = (leftWrites intersect rightWrites).toList().sorted()
                if (overlap.isEmpty()) continue
                val leftPriority = taskById[left.enterTaskId]?.priority ?: 0
                val rightPriority = taskById[right.enterTaskId]?.priority ?: 0
                val equalPriority = leftPriority == rightPriority
                findings += AutomationLintFinding(
                    code = AutomationLintCode.PRIORITY_CONFLICT,
                    severity = if (equalPriority) AutomationLintSeverity.BLOCKING else AutomationLintSeverity.WARNING,
                    profileIds = listOf(left.id, right.id).sorted(),
                    profileNames = listOf(left.name, right.name),
                    title = "Priority conflict",
                    detail = "${left.name} and ${right.name} can both write ${overlap.joinToString()} while their enter-task priorities are $leftPriority and $rightPriority.",
                    suggestedFix = if (equalPriority) {
                        "Raise one task priority or make the contexts mutually exclusive."
                    } else {
                        "Confirm that the higher-priority task should win, or make the contexts mutually exclusive."
                    },
                )
            }
        }

        interProfileCycles(profiles, tasks).forEach { cycle ->
            findings += AutomationLintFinding(
                code = AutomationLintCode.INTER_PROFILE_LOOP,
                severity = AutomationLintSeverity.WARNING,
                profileIds = cycle.map(Profile::id),
                profileNames = cycle.map(Profile::name),
                title = "Inter-profile loop",
                detail = "Profiles form a direct task cycle: ${cycle.joinToString(" → ") { it.name }} → ${cycle.first().name}.",
                suggestedFix = "Remove one task.run edge or add an explicit state guard so the chain cannot retrigger itself.",
            )
        }

        return AutomationLintReport(findings.toList())
    }

    private fun hasRetriggerGuard(profile: Profile, enterTask: Task?): Boolean {
        if (profile.cooldownSec > 0) return true
        if (profile.contexts.any { spec ->
                val dwell = listOf("dwellMillis", "dwellMs", "dwellSeconds", "dwellSec")
                    .firstNotNullOfOrNull { spec.config[it]?.toLongOrNull() }
                dwell != null && dwell > 0L
            }
        ) return true
        return enterTask?.actions.orEmpty().any { action ->
            action.condition?.trim()?.isNotEmpty() == true ||
                action.args["idempotent"].equals("true", ignoreCase = true) ||
                action.args["guard"].equals("true", ignoreCase = true)
        }
    }

    private fun settingWrites(profile: Profile, tasks: List<Task>): List<SettingWrite> =
        AutomationSensitivityRegistry.reachableTasks(profile, tasks)
            .flatMap { task -> task.actions.mapNotNull { action -> action.toSettingWrite(task) } }
            .distinctBy { it.taskId to (it.key to it.automaticallyReversed) }

    private fun ActionSpec.toSettingWrite(task: Task): SettingWrite? {
        val key = when (type) {
            "wifi.toggle" -> "wifi"
            "bluetooth.toggle" -> "bluetooth"
            "brightness.set" -> "brightness"
            "volume.set" -> "volume:${args["stream"].orEmpty().ifBlank { "music" }.lowercase(Locale.US)}"
            "airplane.toggle" -> "airplane"
            "mobile.toggle" -> "mobile"
            "screen.timeout" -> "screen_timeout"
            "dnd.set", "zen.rule.set", "zen.rule.clear" -> "dnd"
            "ringer.set" -> "ringer"
            "torch.set" -> "torch"
            "ime.set" -> "ime"
            "screen.off", "wake" -> "screen"
            "tile.set" -> "tile:${args["slot"].orEmpty().ifBlank { "default" }}"
            "state.temporary" -> {
                val target = args["target_action"]?.trim().orEmpty()
                target.takeIf { it in TEMPORARY_TARGET_ACTIONS }?.let {
                    "temporary:$it:${args["key"].orEmpty().ifBlank { "default" }}"
                }
            }
            else -> null
        } ?: return null
        return SettingWrite(
            taskId = task.id,
            key = key,
            automaticallyReversed = type == "state.temporary",
        )
    }

    private fun contextsMayOverlap(left: Profile, right: Profile): Boolean {
        if (left.contexts.isEmpty() || right.contexts.isEmpty()) return false
        // An explicit expression can contain OR/NOT branches, so an inexpensive pairwise proof
        // would risk blocking a satisfiable branch. Leave those cases as warnings only.
        if (left.contextExpression != null || right.contextExpression != null) return true
        // Legacy profiles are implicit ANDs. Any contradictory cross-pair proves that the two
        // profiles cannot be active together; otherwise they remain conservatively overlapping.
        return left.contexts.none { leftSpec ->
            right.contexts.any { rightSpec -> !contextPairMayOverlap(leftSpec, rightSpec) }
        }
    }

    private fun contextPairMayOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        if (left.invert != right.invert) {
            // A positive and a negated copy of the same condition are contradictory. For
            // different conditions, the positive condition can still satisfy the negation.
            return left.copy(invert = false) != right.copy(invert = false)
        }
        if (left.invert && right.invert) return true
        if (left.type != right.type) return true
        return when (left.type) {
            ContextType.APPLICATION -> applicationOverlap(left, right)
            ContextType.TIME -> timeOverlap(left, right)
            ContextType.DAY -> DaySchedule.parse(first(left, "days", "day"))
                .intersect(DaySchedule.parse(first(right, "days", "day"))).isNotEmpty()
            ContextType.LOCATION -> locationOverlap(left, right)
            ContextType.STATE -> stateOverlap(left, right)
            ContextType.EVENT -> eventOverlap(left, right)
            ContextType.PLUGIN -> pluginOverlap(left, right)
        }
    }

    private fun applicationOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPackages = csv(first(left, "package", "packages", "apps"))
        val rightPackages = csv(first(right, "package", "packages", "apps"))
        if (leftPackages.isNotEmpty() && rightPackages.isNotEmpty() && leftPackages.intersect(rightPackages).isEmpty()) return false
        val leftComponent = first(left, "component", "activity", "class")
        val rightComponent = first(right, "component", "activity", "class")
        return leftComponent.isBlank() || rightComponent.isBlank() || leftComponent == rightComponent ||
            leftComponent.contains('*') || rightComponent.contains('*')
    }

    private fun timeOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftStart = parseClock(first(left, "start", "from")) ?: return true
        val leftEnd = parseClock(first(left, "end", "to")) ?: return true
        val rightStart = parseClock(first(right, "start", "from")) ?: return true
        val rightEnd = parseClock(first(right, "end", "to")) ?: return true
        return (0 until MINUTES_PER_DAY).any { minute ->
            inWindow(minute, leftStart, leftEnd) && inWindow(minute, rightStart, rightEnd)
        }
    }

    private fun locationOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        if (first(left, "outside", "inverted").equals("true", true) ||
            first(right, "outside", "inverted").equals("true", true)
        ) return true
        val leftLat = first(left, "latitude", "lat").toDoubleOrNull() ?: return true
        val leftLon = first(left, "longitude", "lon", "lng").toDoubleOrNull() ?: return true
        val rightLat = first(right, "latitude", "lat").toDoubleOrNull() ?: return true
        val rightLon = first(right, "longitude", "lon", "lng").toDoubleOrNull() ?: return true
        val leftRadius = first(left, "radiusMeters", "radius").toDoubleOrNull() ?: return true
        val rightRadius = first(right, "radiusMeters", "radius").toDoubleOrNull() ?: return true
        return FossGeofenceEvaluator.distanceMeters(leftLat, leftLon, rightLat, rightLon) <= leftRadius + rightRadius
    }

    private fun stateOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPredicate = statePredicate(left) ?: return true
        val rightPredicate = statePredicate(right) ?: return true
        if (normalizeStateKey(leftPredicate.first) != normalizeStateKey(rightPredicate.first)) return true
        val leftValue = leftPredicate.third.lowercase(Locale.US)
        val rightValue = rightPredicate.third.lowercase(Locale.US)
        if (leftPredicate.second == "=" && rightPredicate.second == "=" && leftValue != rightValue) return false
        val leftNumber = leftValue.toIntOrNull()
        val rightNumber = rightValue.toIntOrNull()
        if (leftNumber != null && rightNumber != null) {
            val leftRange = numericRange(leftPredicate.second, leftNumber)
            val rightRange = numericRange(rightPredicate.second, rightNumber)
            return leftRange.first <= rightRange.second && rightRange.first <= leftRange.second
        }
        return true
    }

    private fun eventOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftEvent = first(left, "event")
        val rightEvent = first(right, "event")
        return leftEvent.isBlank() || rightEvent.isBlank() || leftEvent.equals(rightEvent, true)
    }

    private fun pluginOverlap(left: ContextSpec, right: ContextSpec): Boolean {
        val leftPackage = first(left, "package")
        val rightPackage = first(right, "package")
        if (leftPackage.isNotBlank() && rightPackage.isNotBlank() && !leftPackage.equals(rightPackage, true)) return false
        val leftBundle = first(left, "bundleJson").ifBlank { "{}" }
        val rightBundle = first(right, "bundleJson").ifBlank { "{}" }
        return leftBundle == rightBundle || leftBundle == "{}" || rightBundle == "{}"
    }

    private fun interProfileCycles(profiles: List<Profile>, tasks: List<Task>): List<List<Profile>> {
        val profilesByEnterTask = profiles.groupBy(Profile::enterTaskId)
        val edges = profiles.associateWith { profile ->
            AutomationSensitivityRegistry.reachableTasks(profile, tasks)
                .flatMap { task -> directTaskReferences(task, tasks) }
                .flatMap { target -> profilesByEnterTask[target.id].orEmpty() }
                .filter { it.id != profile.id }
                .distinctBy(Profile::id)
        }
        val cycles = linkedMapOf<String, List<Profile>>()
        fun walk(start: Profile, current: Profile, path: List<Profile>) {
            edges[current].orEmpty().forEach { next ->
                if (next.id == start.id) {
                    val cycle = path
                    val rotations = cycle.indices.map { index -> cycle.drop(index) + cycle.take(index) }
                    val canonical = rotations.minBy { rotation -> rotation.joinToString("\u0000") { it.id.toString() } }
                    cycles[canonical.joinToString(",") { it.id.toString() }] = canonical
                } else if (next !in path && path.size < profiles.size) {
                    walk(start, next, path + next)
                }
            }
        }
        profiles.forEach { walk(it, it, listOf(it)) }
        return cycles.values.toList()
    }

    private fun directTaskReferences(task: Task, tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.trim().lowercase(Locale.US) }
        return task.actions.filter { it.type == "task.run" }.flatMap { action ->
            val reference = listOf("task", "name", "id")
                .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                ?: return@flatMap emptyList()
            if (reference.contains('%') || reference.contains("{{")) return@flatMap emptyList()
            reference.toLongOrNull()?.let { id -> listOfNotNull(byId[id]) }
                ?: byName[reference.lowercase(Locale.US)].orEmpty()
        }
    }

    private fun statePredicate(spec: ContextSpec): Triple<String, String, String>? {
        val predicate = spec.config["predicate"]?.trim().orEmpty()
        if (predicate.isNotBlank()) return parsePredicate(predicate)
        val key = spec.config["key"]?.trim().orEmpty()
        val value = spec.config["value"]?.trim().orEmpty()
        if (key.isBlank() || value.isBlank()) return null
        return Triple(key.lowercase(Locale.US), spec.config["operator"]?.trim().orEmpty().ifBlank { "=" }, value)
    }

    private fun parsePredicate(value: String): Triple<String, String, String>? {
        val operator = listOf(">=", "<=", ">", "<", "=").firstOrNull { value.contains(it) } ?: return null
        val parts = value.split(operator, limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Triple(parts[0].trim().lowercase(Locale.US), operator, parts[1].trim())
    }

    private fun numericRange(operator: String, value: Int): Pair<Int, Int> = when (operator) {
        ">=" -> value to Int.MAX_VALUE
        ">" -> (value + 1) to Int.MAX_VALUE
        "<=" -> Int.MIN_VALUE to value
        "<" -> Int.MIN_VALUE to (value - 1)
        else -> value to value
    }

    private fun first(spec: ContextSpec, vararg keys: String): String =
        keys.firstNotNullOfOrNull { spec.config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun normalizeStateKey(key: String): String = when (key.lowercase(Locale.US)) {
        "wifissid", "wifi_network", "wifi_connected" -> "wifi"
        "battery_level", "batterypercent" -> "battery"
        "headphones", "headphones_connected" -> "headset"
        else -> key.lowercase(Locale.US)
    }

    private fun csv(value: String): Set<String> = value.split(',', ';')
        .map { it.trim().lowercase(Locale.US) }
        .filter(String::isNotBlank)
        .toSet()

    private fun parseClock(value: String): Int? {
        val parts = value.split(":")
        if (parts.size !in 1..2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
    }

    private fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start..end else minute >= start || minute <= end

    private data class SettingWrite(
        val taskId: Long,
        val key: String,
        val automaticallyReversed: Boolean,
    )

    private val TEMPORARY_TARGET_ACTIONS = setOf("brightness.set", "volume.set", "ringer.set", "dnd.set")
    private const val MINUTES_PER_DAY = 24 * 60
}
