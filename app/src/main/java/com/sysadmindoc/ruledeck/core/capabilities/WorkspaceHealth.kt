package com.sysadmindoc.ruledeck.core.capabilities

import com.sysadmindoc.ruledeck.i18n.tr
import android.content.Context
import com.sysadmindoc.ruledeck.core.model.Task

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
    fun taskProblems(
        task: Task,
        // `Run Shell` is judged per step: never for Shizuku in root mode, and for Shizuku itself otherwise.
        shizukuItself: (() -> Boolean)? = null,
        reqMet: (CapabilityRequirement) -> Boolean,
    ): List<String> =
        task.actions.mapNotNull { action ->
            val type = action.type
            val cap = ActionCapabilityRegistry.get(type)
            val met = when {
                CapabilityState.runsAsRoot(action) -> true
                type == "shell.run" && shizukuItself != null -> shizukuItself()
                else -> reqMet(cap.requirement)
            }
            when {
                cap.level == CapabilityLevel.Unsupported -> tr("%1\$s: unsupported", type)
                cap.blocking && cap.requirement != CapabilityRequirement.None && !met ->
                    tr("%1\$s: needs %2\$s", type, CapabilityState.shortLabel(cap.requirement))
                else -> null
            }
        }.distinct()

    /** All blocked tasks in [tasks], with the live requirement states evaluated once. */
    fun blockedTasks(tasks: List<Task>, context: Context): List<BlockedTask> {
        val met = CapabilityRequirement.entries.associateWith { CapabilityState.isMetLive(it, context) }
        val shizukuItself = CapabilityState.shizukuItself(context, live = true)
        return tasks.mapNotNull { task ->
            val problems = taskProblems(task, shizukuItself = { shizukuItself }) { met.getValue(it) }
            if (problems.isEmpty()) null else BlockedTask(task.id, task.name, problems)
        }
    }
}
