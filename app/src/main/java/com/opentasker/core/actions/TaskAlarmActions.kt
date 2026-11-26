package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.SUB_TASK_PARAM_PREFIX
import com.opentasker.core.scheduling.TaskAlarms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Set Task Alarm` — run a task at one moment, by an exact alarm of its own that wakes a sleeping
 * phone at the second and survives a restart of the app and a reboot. See [TaskAlarms].
 *
 * Args:
 *   - "task":      the task to run
 *   - "project":   its project, when the name alone is not unique
 *   - "key":       the alarm's name (default: the task's name) — setting a key again replaces its
 *                  alarm, `Cancel Task Alarm` with the same key forgets it
 *   - "at":        the moment, in epoch milliseconds (what `Add to Date/Time` writes)
 *   - "seconds":   or: this many seconds from now — used when "at" is empty
 *   - "param:<n>": handed to the task as the local variable `%<n>`; it also gets `%alarm_key`,
 *                  `%alarm_due` (epoch milliseconds) and `%alarm_late` (seconds)
 *   - "var":       optional, receives the moment in epoch milliseconds
 *
 * A moment already past runs the task within the minute.
 */
class TaskAlarmSetAction : Action {
    override val id = "task.alarm.set"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val task = args["task"].orEmpty().trim()
        if (task.isEmpty()) return ActionResult.Failure("task name is required")
        val project = args["project"].orEmpty().trim()
        val key = args["key"].orEmpty().trim().ifEmpty { task }
        val now = System.currentTimeMillis()
        val atRaw = args["at"].orEmpty().trim()
        val secondsRaw = args["seconds"].orEmpty().trim()
        val at = when {
            atRaw.isNotEmpty() -> atRaw.toLongOrNull() ?: return ActionResult.Failure("at is not epoch milliseconds: $atRaw")
            secondsRaw.isNotEmpty() -> now + 1000L * (secondsRaw.toLongOrNull()?.takeIf { it >= 0 }
                ?: return ActionResult.Failure("seconds is not a number: $secondsRaw"))
            else -> return ActionResult.Failure("give the moment: at (epoch milliseconds) or seconds (from now)")
        }
        val known = withContext(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            val projectId = if (project.isEmpty()) null else
                db.projectDao().getAll().firstOrNull { it.name.equals(project, ignoreCase = true) }?.id
                    ?: return@withContext -1
            db.taskDao().getAll().count { it.name.equals(task, ignoreCase = true) && (projectId == null || it.projectId == projectId) }
        }
        when {
            known == -1 -> return ActionResult.Failure("no such project: $project")
            known == 0 -> return ActionResult.Failure("no such task: $task")
            known > 1 -> return ActionResult.Failure("$known tasks are named '$task' — name the project")
        }
        val params = buildMap {
            args.forEach { (name, value) ->
                if (name.startsWith(SUB_TASK_PARAM_PREFIX)) this[name.removePrefix(SUB_TASK_PARAM_PREFIX)] = value
            }
        }
        TaskAlarms.set(ctx.app, TaskAlarms.Order(key = key, task = task, project = project, atMs = at, params = params))
        args["var"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, at.toString()) }
        ctx.logger("Task alarm '$key': '$task' in ${(at - now) / 1000} s")
        return ActionResult.Success
    }
}

/**
 * `Cancel Task Alarm` — forget the alarm set under a key. Nothing waiting under it is not an error.
 *
 * Args:
 *   - "key":   the alarm's name
 *   - "store": optional variable, receives `true` when an alarm was waiting, `false` otherwise
 */
class TaskAlarmCancelAction : Action {
    override val id = "task.alarm.cancel"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val key = args["key"].orEmpty().trim()
        if (key.isEmpty()) return ActionResult.Failure("the alarm's key is required")
        val had = TaskAlarms.cancel(ctx.app, key)
        args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, had.toString()) }
        ctx.logger(if (had) "Task alarm '$key' cancelled" else "Task alarm '$key': nothing was waiting")
        return ActionResult.Success
    }
}
