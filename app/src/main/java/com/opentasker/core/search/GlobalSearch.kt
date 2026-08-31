package com.opentasker.core.search

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import java.util.Locale

enum class GlobalSearchResultKind {
    PROFILE,
    TASK,
    ACTION,
    VARIABLE,
    SCENE,
}

data class GlobalSearchResult(
    val kind: GlobalSearchResultKind,
    val entityId: Long,
    val title: String,
    val detail: String,
    val projectId: Long?,   // fork: null = Unfiled
    val actionIndex: Int? = null,
    val variableName: String? = null,
)

/**
 * Searches the current in-memory entity snapshots. Keeping this pure makes the index live as
 * Room flows change, while keeping search unavailable to secret plaintext and network services.
 */
fun searchGlobalEntities(
    query: String,
    profiles: List<Profile>,
    tasks: List<Task>,
    variables: List<Variable>,
    scenes: List<Scene>,
): List<GlobalSearchResult> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT).removePrefix("%")
    if (normalizedQuery.isEmpty()) return emptyList()

    val taskNames = tasks.associateBy { it.id }
    val matching = buildList {
        variables.forEach { variable ->
            val searchable = listOf(
                variable.name,
                if (variable.isSecret) "" else variable.value,
            ).joinToString(" ")
            if (searchable.contains(normalizedQuery, ignoreCase = true)) {
                add(
                    GlobalSearchResult(
                        kind = GlobalSearchResultKind.VARIABLE,
                        entityId = stableVariableId(variable),
                        title = "%${variable.name}",
                        detail = if (variable.isSecret) "Secret variable" else "Global variable",
                        projectId = variable.projectId,
                        variableName = variable.name,
                    )
                )
            }
        }

        tasks.forEach { task ->
            val taskSearchable = buildString {
                append(task.name)
                append(' ')
                task.actions.forEach { action ->
                    append(action.type)
                    append(' ')
                    append(action.label.orEmpty())
                    append(' ')
                    append(action.condition.orEmpty())
                    append(' ')
                    action.args.forEach { (key, value) ->
                        append(key)
                        append(' ')
                        append(value)
                        append(' ')
                    }
                }
            }
            if (taskSearchable.contains(normalizedQuery, ignoreCase = true)) {
                add(
                    GlobalSearchResult(
                        kind = GlobalSearchResultKind.TASK,
                        entityId = task.id,
                        title = task.name,
                        detail = "${task.actions.size} actions",
                        projectId = task.projectId,
                    )
                )
            }
            task.actions.forEachIndexed { index, action ->
                val actionSearchable = buildString {
                    append(action.type)
                    append(' ')
                    append(action.label.orEmpty())
                    append(' ')
                    append(action.condition.orEmpty())
                    action.args.forEach { (key, value) ->
                        append(' ')
                        append(key)
                        append(' ')
                        append(value)
                    }
                }
                if (actionSearchable.contains(normalizedQuery, ignoreCase = true)) {
                    add(
                        GlobalSearchResult(
                            kind = GlobalSearchResultKind.ACTION,
                            entityId = task.id,
                            title = action.label?.takeIf { it.isNotBlank() } ?: action.type,
                            detail = "${task.name} • action ${index + 1}",
                            projectId = task.projectId,
                            actionIndex = index,
                        )
                    )
                }
            }
        }

        profiles.forEach { profile ->
            val enterTask = taskNames[profile.enterTaskId]
            val exitTask = profile.exitTaskId?.let(taskNames::get)
            val contextText = buildString {
                profile.contexts.forEach { context ->
                    append(context.type.name)
                    context.config.forEach { (key, value) ->
                        append(' ')
                        append(key)
                        append(' ')
                        append(value)
                    }
                }
            }
            val searchable = listOf(
                profile.name,
                profile.group.orEmpty(),
                enterTask?.name.orEmpty(),
                exitTask?.name.orEmpty(),
                contextText,
            ).joinToString(" ")
            if (searchable.contains(normalizedQuery, ignoreCase = true)) {
                add(
                    GlobalSearchResult(
                        kind = GlobalSearchResultKind.PROFILE,
                        entityId = profile.id,
                        title = profile.name,
                        detail = "Profile${profile.group?.let { " • $it" }.orEmpty()}",
                        projectId = profile.projectId,
                    )
                )
            }
        }

        scenes.forEach { scene ->
            val elementsText = scene.elements.joinToString(" ") { element ->
                buildString {
                    append(element.type.name)
                    element.config.forEach { (key, value) ->
                        append(' ')
                        append(key)
                        append(' ')
                        append(value)
                    }
                    element.tapTaskId?.let { append(' ').append(taskNames[it]?.name.orEmpty()) }
                    element.longPressTaskId?.let { append(' ').append(taskNames[it]?.name.orEmpty()) }
                }
            }
            val searchable = "${scene.name} $elementsText"
            if (searchable.contains(normalizedQuery, ignoreCase = true)) {
                add(
                    GlobalSearchResult(
                        kind = GlobalSearchResultKind.SCENE,
                        entityId = scene.id,
                        title = scene.name,
                        detail = "${scene.elements.size} elements",
                        projectId = scene.projectId,
                    )
                )
            }
        }
    }
    return matching.sortedWith(compareBy({ it.kind.ordinal }, { it.title.lowercase(Locale.ROOT) }, { it.entityId }))
}

/** Variable names are not database IDs; this is only a stable UI key for a result row. */
private fun stableVariableId(variable: Variable): Long =
    (variable.projectId * 31L + variable.name.lowercase(Locale.ROOT).hashCode()).coerceAtLeast(0L)
