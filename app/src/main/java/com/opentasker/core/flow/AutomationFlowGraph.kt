package com.opentasker.core.flow

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

data class AutomationFlowGraph(
    val profileId: Long,
    val title: String,
    val nodes: List<AutomationFlowNode>,
    val edges: List<AutomationFlowEdge>,
    val warnings: List<String> = emptyList(),
) {
    val contextNodes: List<AutomationFlowNode>
        get() = nodes.filter { it.kind == AutomationFlowNodeKind.CONTEXT }

    val enterTaskNode: AutomationFlowNode?
        get() = nodes.firstOrNull { it.kind == AutomationFlowNodeKind.ENTER_TASK }

    val exitTaskNode: AutomationFlowNode?
        get() = nodes.firstOrNull { it.kind == AutomationFlowNodeKind.EXIT_TASK }

    fun actionNodesFor(taskNodeId: String): List<AutomationFlowNode> {
        val orderedIds = buildList {
            var cursor = edges.firstOrNull { it.fromId == taskNodeId }?.toId
            while (cursor != null) {
                val node = nodes.firstOrNull { it.id == cursor } ?: break
                if (node.kind != AutomationFlowNodeKind.ACTION) break
                add(node.id)
                cursor = edges.firstOrNull { it.fromId == node.id }?.toId
            }
        }
        return orderedIds.mapNotNull { id -> nodes.firstOrNull { it.id == id } }
    }

    fun incomingEdgeLabel(nodeId: String): String? =
        edges.firstOrNull { it.toId == nodeId }?.label

    fun accessibilitySummary(): String {
        val actionCount = nodes.count { it.kind == AutomationFlowNodeKind.ACTION }
        val enterTask = enterTaskNode?.title ?: "missing enter task"
        val exitTask = exitTaskNode?.title ?: "no exit task"
        val warningText = if (warnings.isEmpty()) {
            "no warnings"
        } else {
            "${warnings.size} warning${plural(warnings.size)}"
        }
        return "$title: ${contextNodes.size} context${plural(contextNodes.size)}, " +
            "$actionCount action${plural(actionCount)}, enter task $enterTask, exit task $exitTask, $warningText."
    }
}

data class AutomationFlowNode(
    val id: String,
    val kind: AutomationFlowNodeKind,
    val title: String,
    val detail: String? = null,
    val muted: Boolean = false,
    val target: AutomationFlowTarget? = null,
    val condition: String? = null,
) {
    fun accessibilityLabel(): String {
        val kindName = kind.name.lowercase().replace('_', ' ')
        val parts = buildList {
            add(kindName)
            add(title)
            detail?.takeUnless { it.isBlank() }?.let(::add)
            condition?.takeUnless { it.isBlank() }?.let { add("condition if $it") }
            if (muted) add("inactive")
        }
        return parts.joinToString(". ")
    }
}

enum class AutomationFlowNodeKind {
    PROFILE,
    CONTEXT,
    ENTER_TASK,
    EXIT_TASK,
    ACTION,
    MISSING,
}

sealed interface AutomationFlowTarget {
    data class Profile(val profileId: Long) : AutomationFlowTarget
    data class Context(val profileId: Long, val index: Int) : AutomationFlowTarget
    data class Task(val taskId: Long) : AutomationFlowTarget
    data class Action(val taskId: Long, val index: Int) : AutomationFlowTarget
}

data class AutomationFlowEdge(
    val fromId: String,
    val toId: String,
    val label: String,
)

object AutomationFlowGraphBuilder {
    fun build(profile: Profile, tasks: List<Task>): AutomationFlowGraph =
        build(profile, tasks.associateBy { it.id })

    fun build(profile: Profile, tasksById: Map<Long, Task>): AutomationFlowGraph {
        val nodes = mutableListOf<AutomationFlowNode>()
        val edges = mutableListOf<AutomationFlowEdge>()
        val warnings = mutableListOf<String>()
        val profileNodeId = "profile:${profile.id}"

        nodes += AutomationFlowNode(
            id = profileNodeId,
            kind = AutomationFlowNodeKind.PROFILE,
            title = profile.name,
            detail = listOf(
                if (profile.enabled) "Enabled" else "Disabled",
                "Mode ${profile.automationMode.name.lowercase()}",
                "Cooldown ${profile.cooldownSec}s",
            ).joinToString(" - "),
            muted = !profile.enabled,
            target = AutomationFlowTarget.Profile(profile.id),
        )

        if (profile.contexts.isEmpty()) {
            warnings += "Profile has no contexts."
        }

        profile.contexts.forEachIndexed { index, context ->
            val contextNodeId = "profile:${profile.id}:context:$index"
            nodes += context.toNode(contextNodeId, profile.id, index)
            edges += AutomationFlowEdge(
                fromId = contextNodeId,
                toId = profileNodeId,
                label = if (context.invert) "must not match" else "must match",
            )
        }

        val enterTaskNodeId = addTaskLane(
            nodes = nodes,
            edges = edges,
            warnings = warnings,
            sourceNodeId = profileNodeId,
            profileId = profile.id,
            profileName = profile.name,
            taskId = profile.enterTaskId,
            task = tasksById[profile.enterTaskId],
            kind = AutomationFlowNodeKind.ENTER_TASK,
            edgeLabel = "enter",
        )

        if (enterTaskNodeId == null) {
            warnings += "Enter task ${profile.enterTaskId} is missing."
        }

        profile.exitTaskId?.let { exitTaskId ->
            val exitTaskNodeId = addTaskLane(
                nodes = nodes,
                edges = edges,
                warnings = warnings,
                sourceNodeId = profileNodeId,
                profileId = profile.id,
                profileName = profile.name,
                taskId = exitTaskId,
                task = tasksById[exitTaskId],
                kind = AutomationFlowNodeKind.EXIT_TASK,
                edgeLabel = "exit",
            )
            if (exitTaskNodeId == null) {
                warnings += "Exit task $exitTaskId is missing."
            }
        }

        return AutomationFlowGraph(
            profileId = profile.id,
            title = profile.name,
            nodes = nodes,
            edges = edges,
            warnings = warnings.distinct(),
        )
    }

    private fun addTaskLane(
        nodes: MutableList<AutomationFlowNode>,
        edges: MutableList<AutomationFlowEdge>,
        warnings: MutableList<String>,
        sourceNodeId: String,
        profileId: Long,
        profileName: String,
        taskId: Long,
        task: Task?,
        kind: AutomationFlowNodeKind,
        edgeLabel: String,
    ): String? {
        val taskNodeId = "${edgeLabel}-task:$taskId"
        if (task == null) {
            nodes += AutomationFlowNode(
                id = taskNodeId,
                kind = AutomationFlowNodeKind.MISSING,
                title = "Missing ${edgeLabel} task",
                detail = "Task id $taskId is referenced by $profileName",
                muted = true,
                target = AutomationFlowTarget.Profile(profileId),
            )
            edges += AutomationFlowEdge(sourceNodeId, taskNodeId, edgeLabel)
            return null
        }

        nodes += AutomationFlowNode(
            id = taskNodeId,
            kind = kind,
            title = task.name,
            detail = "${task.actions.size} action${plural(task.actions.size)} - priority ${task.priority}",
            target = AutomationFlowTarget.Task(taskId),
        )
        edges += AutomationFlowEdge(sourceNodeId, taskNodeId, edgeLabel)

        if (task.actions.isEmpty()) {
            warnings += "${task.name} has no actions."
        }

        var previousNodeId = taskNodeId
        task.actions.forEachIndexed { index, action ->
            val actionNodeId = "$taskNodeId:action:$index"
            nodes += action.toNode(actionNodeId, taskId, index)
            edges += AutomationFlowEdge(
                fromId = previousNodeId,
                toId = actionNodeId,
                label = action.edgeLabel(index),
            )
            previousNodeId = actionNodeId
        }
        return taskNodeId
    }
}

private fun ContextSpec.toNode(id: String, profileId: Long, index: Int): AutomationFlowNode =
    AutomationFlowNode(
        id = id,
        kind = AutomationFlowNodeKind.CONTEXT,
        title = "Context ${index + 1}: ${type.name.lowercase().replaceFirstChar { it.uppercase() }}",
        detail = listOfNotNull(
            if (invert) "Inverted" else null,
            config.summaryOrNull(),
        ).joinToString(" - ").ifBlank { "No parameters" },
        muted = invert,
        target = AutomationFlowTarget.Context(profileId, index),
    )

private fun ActionSpec.toNode(id: String, taskId: Long, index: Int): AutomationFlowNode {
    val subTaskRef = if (type == "task.run") {
        listOf("task", "name", "id").firstNotNullOfOrNull { args[it]?.trim()?.takeUnless(String::isBlank) }
    } else {
        null
    }
    val title = when {
        !label.isNullOrBlank() -> label
        subTaskRef != null -> "Step ${index + 1}: run sub-task \"$subTaskRef\""
        else -> "Step ${index + 1}: $type"
    }
    return AutomationFlowNode(
        id = id,
        kind = AutomationFlowNodeKind.ACTION,
        title = title,
        detail = listOfNotNull(
            if (subTaskRef != null) "sub-task -> $subTaskRef" else type,
            args.summaryOrNull(),
            if (continueOnError) "continues after error" else null,
        ).joinToString(" - "),
        target = AutomationFlowTarget.Action(taskId, index),
        condition = condition?.trim()?.takeUnless { it.isBlank() },
    )
}

private fun ActionSpec.edgeLabel(index: Int): String {
    val trimmedCondition = condition?.trim()?.takeUnless { it.isBlank() }
    return trimmedCondition?.let { "if ${it.safePreview()}" } ?: if (index == 0) "step 1" else "then"
}

private fun Map<String, String>.summaryOrNull(limit: Int = 3): String? {
    if (isEmpty()) return null
    val visible = entries
        .sortedBy { it.key }
        .take(limit)
        .joinToString(", ") { (key, value) -> "$key=${value.safePreview()}" }
    val hiddenCount = size - limit
    return if (hiddenCount > 0) "$visible, +$hiddenCount more" else visible
}

private fun String.safePreview(maxLength: Int = 36): String =
    replace(Regex("\\s+"), " ")
        .let { value -> if (value.length <= maxLength) value else value.take(maxLength - 1) + "..." }

private fun plural(count: Int): String = if (count == 1) "" else "s"
