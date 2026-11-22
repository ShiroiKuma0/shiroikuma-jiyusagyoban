package com.opentasker.core.actions

import com.opentasker.core.engine.EventTaskVariables
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_REF_KEYS
import com.opentasker.core.engine.SUB_TASK_RETURN_KEY
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy

/** Value kinds exposed by an action for downstream template inputs. */
enum class ActionValueType {
    ANY,
    TEXT,
    NUMBER,
    BOOLEAN,
    JSON,
    ARRAY,
}

/** Storage namespace used when an output is rendered as a template reference. */
enum class ActionOutputScope {
    INFERRED,
    GLOBAL,
}

/** A stable, metadata-owned declaration of one variable an action may produce. */
data class ActionOutputDefinition(
    val key: String,
    val type: ActionValueType,
    val nameArgumentKeys: List<String> = emptyList(),
    val defaultName: String? = null,
    val suffix: String = "",
    val scope: ActionOutputScope = ActionOutputScope.INFERRED,
    val omitWhenArgumentsPresent: List<String> = emptyList(),
    val basePath: Boolean = false,
    val requiredArgumentValues: Map<String, Set<String>> = emptyMap(),
) {
    fun resolveName(args: Map<String, String>): String? {
        if (omitWhenArgumentsPresent.any { !args[it].isNullOrBlank() }) return null
        if (requiredArgumentValues.any { (key, allowed) ->
                args[key]?.trim()?.lowercase() !in allowed.map(String::lowercase)
            }) return null
        val rawName = nameArgumentKeys
            .asSequence()
            .mapNotNull { args[it] }
            .firstOrNull { it.isNotBlank() }
            ?: defaultName
            ?: return null
        val candidate = rawName.trim().let { raw ->
            if (!basePath) raw else raw.substringBefore('.').substringBefore('[')
        }
        val normalizedBase = VariableNamePolicy.normalize(candidate) ?: return null
        return if (scope == ActionOutputScope.GLOBAL) {
            VariableNamePolicy.promoteToGlobal(normalizedBase + suffix)
        } else {
            VariableNamePolicy.normalize(normalizedBase + suffix)
        }
    }
}

/** A resolved output with enough provenance for the editor to label it as a step chip. */
data class ResolvedActionOutput(
    val key: String,
    val name: String,
    val type: ActionValueType,
    val scope: ActionOutputScope,
    val actionType: String,
    val actionIndex: Int? = null,
) {
    /** Canonical template text. It is deliberately ordinary text in [ActionSpec.args]. */
    val reference: String
        get() = when {
            type == ActionValueType.ARRAY -> "{{ array.$name }}"
            scope == ActionOutputScope.GLOBAL ||
                (scope == ActionOutputScope.INFERRED && VariableNamePolicy.isGlobal(name)) -> {
                "{{ global.$name }}"
            }
            else -> "{{ $name }}"
        }
}

fun ActionMetadata.resolveOutputs(
    action: ActionSpec,
    actionIndex: Int? = null,
): List<ResolvedActionOutput> = outputs.mapNotNull { definition ->
    definition.resolveName(action.args)?.let { name ->
        ResolvedActionOutput(
            key = definition.key,
            name = name,
            type = definition.type,
            scope = definition.scope,
            actionType = action.type,
            actionIndex = actionIndex,
        )
    }
} + multipleVariablesOutputs(action, actionIndex)

/**
 * One output per name a Multiple Variables Set gives a value, which a fixed list can't declare. When
 * the editor hides the names field, a bare word there may be a value typed on a name line, so only
 * the names written as `%name` count.
 */
private fun ActionMetadata.multipleVariablesOutputs(action: ActionSpec, actionIndex: Int?): List<ResolvedActionOutput> {
    if (id != "var.set_multiple") return emptyList()
    val hidden = ActionArgumentSensitivity.isSensitive(id, "names", action.args)
    return multipleVariablesTargets(action.args, writtenAsVariablesOnly = hidden).map { name ->
        ResolvedActionOutput(
            key = "value",
            name = name,
            type = ActionValueType.ANY,
            scope = ActionOutputScope.INFERRED,
            actionType = action.type,
            actionIndex = actionIndex,
        )
    }
}

enum class VariableChipScope {
    STEP,
    EVENT,
    GLOBAL,
}

/** A value-only editor option. Secret values are intentionally not part of this type. */
data class VariableChipOption(
    val name: String,
    val type: ActionValueType,
    val scope: VariableChipScope,
    val reference: String,
    val actionIndex: Int? = null,
    val actionType: String? = null,
)

data class EditorEventVariable(
    val name: String,
    val type: ActionValueType,
)

/** Event fields a task can read when a share, SMS, notification, call or broadcast trigger started it. */
val DEFAULT_EDITOR_EVENT_VARIABLES: List<EditorEventVariable> =
    EventTaskVariables.all.map { EditorEventVariable(it.name, it.type) }

/**
 * The event chips for [taskId]'s editor. A task some profile starts gets exactly what those
 * profiles' event contexts hand it, which is nothing for a task that only runs on exit. A task no
 * profile uses yet keeps every event's chips, since it may still be attached to one. So does a
 * task another task runs (a sub-task reads its caller's event fields), and one started by an event
 * context with no event named, since that context fires on every event.
 */
fun editorEventVariablesFor(taskId: Long, profiles: List<Profile>, tasks: List<Task> = emptyList()): List<EditorEventVariable> {
    val usedBy = profiles.filter { it.enterTaskId == taskId || it.exitTaskId == taskId }
    if (usedBy.isEmpty() || calledAsSubTask(taskId, tasks)) return DEFAULT_EDITOR_EVENT_VARIABLES
    val events = usedBy.asSequence()
        .filter { it.enterTaskId == taskId }
        .flatMap { it.contexts }
        .filter { it.type == ContextType.EVENT }
        .map { it.config["event"]?.trim().orEmpty() }
        .toList()
    if (events.any(String::isEmpty)) return DEFAULT_EDITOR_EVENT_VARIABLES
    return events.asSequence()
        .flatMap(EventTaskVariables::declaredFor)
        .distinctBy { it.name }
        .map { EditorEventVariable(it.name, it.type) }
        .toList()
}

/**
 * Whether another task has a step that runs [taskId], or runs a task named only at run time, which
 * might be this one.
 */
private fun calledAsSubTask(taskId: Long, tasks: List<Task>): Boolean {
    val name = tasks.firstOrNull { it.id == taskId }?.name
    return tasks.any { caller ->
        caller.id != taskId && caller.actions.any { spec ->
            val ref = SUB_TASK_REF_KEYS.firstNotNullOfOrNull { spec.args[it]?.trim()?.takeIf(String::isNotBlank) }
            spec.enabled && spec.type == SUB_TASK_ACTION_ID && ref != null &&
                ('%' in ref || "{{" in ref || ref.toLongOrNull() == taskId || ref.equals(name, ignoreCase = true))
        }
    }
}

/**
 * Returns all references visible while editing an action. The current action is excluded when it
 * is being edited, so a step cannot accidentally reference its own output. Global names are
 * included without their values, which keeps secret variables out of the UI data path.
 */
fun typedVariableOptions(
    actions: List<ActionSpec>,
    editingIndex: Int? = null,
    globalNames: Collection<String> = emptyList(),
    eventVariables: List<EditorEventVariable> = DEFAULT_EDITOR_EVENT_VARIABLES,
): List<VariableChipOption> {
    val precedingCount = editingIndex?.coerceIn(0, actions.size) ?: actions.size
    val stepOptions = actions.take(precedingCount).flatMapIndexed { index, action ->
        ActionMetadataRegistry.get(action.type)
            ?.resolveOutputs(action, actionIndex = index)
            .orEmpty()
            .map { output ->
                VariableChipOption(
                    name = output.name,
                    type = output.type,
                    scope = VariableChipScope.STEP,
                    reference = output.reference,
                    actionIndex = index,
                    actionType = action.type,
                )
            }
    }
    val eventOptions = eventVariables.map { event ->
        VariableChipOption(
            name = event.name,
            type = event.type,
            scope = VariableChipScope.EVENT,
            reference = "{{ event.${event.name} }}",
        )
    }
    val globalOptions = globalNames
        .asSequence()
        .mapNotNull(VariableNamePolicy::normalize)
        .distinct()
        .map { name ->
            VariableChipOption(
                name = name,
                type = ActionValueType.ANY,
                scope = VariableChipScope.GLOBAL,
                reference = "{{ global.$name }}",
            )
        }
        .toList()
    return stepOptions + eventOptions + globalOptions
}

/** Whether a metadata field can consume a declared output without an implicit type cast. */
fun ActionField.acceptsVariableType(type: ActionValueType): Boolean {
    // A list's items can be a whole array, like %arr() in For Each's values.
    if (listInput && type == ActionValueType.ARRAY) return true
    inputType?.let { expected ->
        return type == ActionValueType.ANY || type == expected
    }
    return when (fieldType) {
        FieldType.TEXT,
        FieldType.MULTILINE,
        -> type in setOf(
            ActionValueType.ANY,
            ActionValueType.TEXT,
            ActionValueType.NUMBER,
            ActionValueType.BOOLEAN,
            ActionValueType.JSON,
        )
        FieldType.NUMBER -> type == ActionValueType.ANY || type == ActionValueType.NUMBER
        FieldType.CHECKBOX -> type == ActionValueType.ANY || type == ActionValueType.BOOLEAN
        FieldType.DROPDOWN,
        FieldType.TASK,
        FieldType.APP,
        FieldType.FILE,
        -> false
    }
}

/**
 * Inserts a canonical template token while preserving an existing hand-authored expression. A
 * [list] field gets the token as one more item, after a comma unless the text already ends in one,
 * or as the end of a range when the text ends in a colon (`1:` then a chip makes `1:{{ ... }}`).
 * An array can't end a range, so its chip is always a new item.
 */
fun insertVariableChip(existing: String, option: VariableChipOption, list: Boolean = false): String {
    if (existing.isBlank()) return option.reference
    if (list) {
        val written = existing.trimEnd()
        if (written.endsWith(":") && option.type != ActionValueType.ARRAY) return written + option.reference
        if (!written.endsWith(",")) return "$written, ${option.reference}"
    }
    val separator = if (existing.last().isWhitespace()) "" else " "
    return existing + separator + option.reference
}

private fun output(
    key: String,
    type: ActionValueType,
    nameArgumentKeys: List<String> = emptyList(),
    defaultName: String? = null,
    suffix: String = "",
    scope: ActionOutputScope = ActionOutputScope.INFERRED,
    omitWhenArgumentsPresent: List<String> = emptyList(),
    basePath: Boolean = false,
    requiredArgumentValues: Map<String, Set<String>> = emptyMap(),
) = ActionOutputDefinition(
    key = key,
    type = type,
    nameArgumentKeys = nameArgumentKeys,
    defaultName = defaultName,
    suffix = suffix,
    scope = scope,
    omitWhenArgumentsPresent = omitWhenArgumentsPresent,
    basePath = basePath,
    requiredArgumentValues = requiredArgumentValues,
)

/**
 * The single variables a step of [actionId] gives a value, by canonical name: its declared outputs
 * other than arrays, and each name a Multiple Variables Set lists, which no output declares.
 */
internal fun writtenScalarVariables(actionId: String, args: Map<String, String>): List<String> =
    declaredActionOutputs(actionId).filter { it.type != ActionValueType.ARRAY }.mapNotNull { it.resolveName(args) } +
        if (actionId == "var.set_multiple") multipleVariablesTargets(args) else emptyList()

/** Output declarations for the built-in actions whose runtime writes variables. */
internal fun declaredActionOutputs(actionId: String): List<ActionOutputDefinition> = when (actionId) {
    "var.set" -> listOf(output("value", ActionValueType.ANY, nameArgumentKeys = listOf("name"), basePath = true))
    "task.run" -> listOf(output("value", ActionValueType.ANY, nameArgumentKeys = listOf(SUB_TASK_RETURN_KEY)))
    "var.persist" -> listOf(output("value", ActionValueType.ANY, nameArgumentKeys = listOf("global_name", "name"), scope = ActionOutputScope.GLOBAL))
    "data.read" -> listOf(
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "data"),
        output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "variable"), defaultName = "data"),
        output("count", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "variable"), defaultName = "data", suffix = "_count"),
    )
    "datetime.format" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "datetime"))
    "datetime.parse", "datetime.add" -> listOf(output("value", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "variable"), defaultName = "datetime"))
    "text.match" -> listOf(
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "match"),
        output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "variable"), defaultName = "match"),
        output("count", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "variable"), defaultName = "match", suffix = "_count"),
    )
    "text.replace" -> listOf(
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "result"),
        output("matches", ActionValueType.ARRAY, nameArgumentKeys = listOf("matches")),
    )
    "text.split" -> listOf(
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "parts"),
        output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "variable"), defaultName = "parts"),
        output("count", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "variable"), defaultName = "parts", suffix = "_count"),
    )
    "text.join" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "joined"))
    "text.substring" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "substring"))
    "text.length" -> listOf(output("value", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "variable"), defaultName = "length"))
    "var.split" -> listOf(output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("name")))
    "var.join" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("name")))
    "var.convert" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "name")))
    "location.get" -> listOf("lat", "lon", "accuracy", "age").map { key ->
        output(key, ActionValueType.NUMBER, nameArgumentKeys = listOf("var"), defaultName = LocationGetAction.DEFAULT_PREFIX, suffix = "_$key")
    }
    "sun.times" -> listOf(
        "sunrise", "sunset", "solar_noon", "civil_dawn", "civil_dusk", "nautical_dawn", "nautical_dusk",
        "astronomical_dawn", "astronomical_dusk", "day_or_night_code", "sunlight_duration",
    ).map { key ->
        output(key, ActionValueType.NUMBER, nameArgumentKeys = listOf("var"), defaultName = SunTimesAction.DEFAULT_PREFIX, suffix = "_$key")
    } + listOf(
        output("day_or_night", ActionValueType.TEXT, nameArgumentKeys = listOf("var"), defaultName = SunTimesAction.DEFAULT_PREFIX, suffix = "_day_or_night"),
        output("custom_morning", ActionValueType.ARRAY, nameArgumentKeys = listOf("var"), defaultName = SunTimesAction.DEFAULT_PREFIX, suffix = "_custom_morning"),
        output("custom_evening", ActionValueType.ARRAY, nameArgumentKeys = listOf("var"), defaultName = SunTimesAction.DEFAULT_PREFIX, suffix = "_custom_evening"),
    )
    "array.push", "array.clear", "array.process" ->listOf(output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("array")))
    "array.pop" -> listOf(
        output("array", ActionValueType.ARRAY, nameArgumentKeys = listOf("array")),
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("to_var")),
    )
    "flow.foreach" -> listOf(output("item", ActionValueType.ANY, nameArgumentKeys = listOf("var"), defaultName = "item"))
    "intent.launch" -> listOf(output("result", ActionValueType.NUMBER, nameArgumentKeys = listOf("result_variable"), requiredArgumentValues = mapOf("mode" to setOf("broadcast"))))
    "plugin.locale.query" -> listOf(output("state", ActionValueType.TEXT, nameArgumentKeys = listOf("resultVariable")))
    "script.termux.run", "script.shizuku.run" -> listOf(
        output("stdout", ActionValueType.TEXT, nameArgumentKeys = listOf("capturePrefix"), suffix = "_stdout"),
        output("stderr", ActionValueType.TEXT, nameArgumentKeys = listOf("capturePrefix"), suffix = "_stderr"),
        output("exit_code", ActionValueType.NUMBER, nameArgumentKeys = listOf("capturePrefix"), suffix = "_exit_code"),
        output("stdout_length", ActionValueType.NUMBER, nameArgumentKeys = listOf("capturePrefix"), suffix = "_stdout_length"),
        output("stderr_length", ActionValueType.NUMBER, nameArgumentKeys = listOf("capturePrefix"), suffix = "_stderr_length"),
    )
    "file.read", "file.list" -> listOf(output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "result"))
    "http.request" -> listOf(
        output("response", ActionValueType.TEXT, nameArgumentKeys = listOf("response_var"), defaultName = "result", omitWhenArgumentsPresent = listOf("output_file")),
        output("status", ActionValueType.NUMBER, nameArgumentKeys = listOf("status_var")),
        output("headers", ActionValueType.TEXT, nameArgumentKeys = listOf("headers_var")),
    )
    "http.get", "http.post" -> listOf(output("response", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "variable"), defaultName = "result"))
    "ping" -> listOf(output("reachable", ActionValueType.BOOLEAN, nameArgumentKeys = listOf("var"), defaultName = "result"))
    "ime.info" -> listOf(
        output("current", ActionValueType.TEXT, nameArgumentKeys = listOf("var"), defaultName = "IME", suffix = "_CURRENT"),
        output("enabled", ActionValueType.TEXT, nameArgumentKeys = listOf("var"), defaultName = "IME", suffix = "_ENABLED"),
        output("count", ActionValueType.NUMBER, nameArgumentKeys = listOf("var"), defaultName = "IME", suffix = "_COUNT"),
    )
    "settings.read" -> listOf(
        output("value", ActionValueType.TEXT, nameArgumentKeys = listOf("var"), defaultName = "Setting"),
    )
    "clipboard.get" -> listOf(
        output("text", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "result"), defaultName = "Clipboard"),
        output("has_text", ActionValueType.BOOLEAN, nameArgumentKeys = listOf("var", "result"), defaultName = "Clipboard", suffix = "_has_text"),
    )
    "contacts.lookup" -> listOf(
        output("id", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_id"),
        output("name", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_name"),
        output("phone", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_phone"),
        output("email", ActionValueType.TEXT, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_email"),
        output("count", ActionValueType.NUMBER, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_count"),
        output("names", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_names"),
        output("phones", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_phones"),
        output("emails", ActionValueType.ARRAY, nameArgumentKeys = listOf("var", "result"), defaultName = "Contact", suffix = "_emails"),
    )
    else -> emptyList()
}
