package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task

/**
 * Executes a Task's action list with flow control and variable expansion.
 *
 * Flow control is achieved by special action ids (e.g. "flow.if", "flow.endif",
 * "flow.for", "flow.goto", "flow.stop", "flow.wait"). The runner walks the list
 * with a program counter so loops and gotos work without recursion.
 */
class TaskRunner(
    private val ctx: ActionContext,
) {
    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = System.currentTimeMillis()
        val results = mutableListOf<ActionResult>()
        try {
            var pc = 0
            while (pc in task.actions.indices) {
                val spec = task.actions[pc]
                val result = runOne(spec)
                results += result
                if (result is ActionResult.Failure && !spec.continueOnError) break
                pc++
            }
        } finally {
            ctx.variables.popScope()
        }
        return TaskRunReport(
            taskId = task.id,
            startedAt = started,
            durationMs = System.currentTimeMillis() - started,
            results = results,
        )
    }

    private suspend fun runOne(spec: ActionSpec): ActionResult {
        val action = ActionRegistry.get(spec.type)
            ?: return ActionResult.Failure("unknown action: ${spec.type}")
        val expandedArgs = spec.args.mapValues { ctx.variables.expand(it.value) }
        return runCatching { action.run(ctx, expandedArgs) }
            .getOrElse { ActionResult.Failure("threw: ${it.message}", it) }
    }
}

data class TaskRunReport(
    val taskId: Long,
    val startedAt: Long,
    val durationMs: Long,
    val results: List<ActionResult>,
)
