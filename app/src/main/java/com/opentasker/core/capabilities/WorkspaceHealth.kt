package com.opentasker.core.capabilities

import android.content.Context
import com.opentasker.core.model.Task

/**
 * Live workspace health: which tasks cannot actually run right now. A task is "blocked" when it
 * contains an action that is either Unsupported (the run-time pre-flight hard-fails the whole task on
 * those) or whose blocking requirement is live-unmet (the action would fail at run time). The same
 * [CapabilityState] checks drive the Setup tab, so the red marks and Setup stay in sync by
 * construction.
 */
object WorkspaceHealth {

    /** One blocked task: its id/name plus a short human label per offending action. */
    data class BlockedTask(val taskId: Long, val taskName: String, val problems: List<String>)

    /** Distinct problems in [task], live-evaluated via [reqMet]; empty = the task may run. */
    fun taskProblems(task: Task, reqMet: (CapabilityRequirement) -> Boolean): List<String> =
        task.actions.map { it.type }.distinct().mapNotNull { type ->
            val cap = ActionCapabilityRegistry.get(type)
            when {
                cap.level == CapabilityLevel.Unsupported -> "$type: unsupported"
                cap.blocking && cap.requirement != CapabilityRequirement.None && !reqMet(cap.requirement) ->
                    "$type: needs ${CapabilityState.shortLabel(cap.requirement)}"
                else -> null
            }
        }

    /** All blocked tasks in [tasks], with the live requirement states evaluated once. */
    fun blockedTasks(tasks: List<Task>, context: Context): List<BlockedTask> {
        val met = CapabilityRequirement.entries.associateWith { CapabilityState.isMetLive(it, context) }
        return tasks.mapNotNull { task ->
            val problems = taskProblems(task) { met.getValue(it) }
            if (problems.isEmpty()) null else BlockedTask(task.id, task.name, problems)
        }
    }
}
