package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import java.util.Date

/**
 * Executes a Task's action list with flow control and variable expansion.
 */
class TaskRunner(
    private val ctx: ActionContext,
) {
    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = System.currentTimeMillis()
        val results = mutableListOf<ActionResult>()
        val traces = mutableListOf<ActionExecutionTrace>()
        try {
            var pc = 0
            while (pc in task.actions.indices) {
                val spec = task.actions[pc]
                val (result, trace) = runOne(pc, spec)
                results += result
                traces += trace
                if (result is ActionResult.Failure && !spec.continueOnError) break
                pc++
            }
        } finally {
            ctx.variables.popScope()
        }
        return TaskRunReport(
            taskId = task.id,
            taskName = task.name,
            startedAt = started,
            durationMs = System.currentTimeMillis() - started,
            results = results,
            traces = traces,
            success = results.all { it is ActionResult.Success || it is ActionResult.Skip }
        )
    }

    private suspend fun runOne(index: Int, spec: ActionSpec): Pair<ActionResult, ActionExecutionTrace> {
        val started = System.currentTimeMillis()
        val action = ActionRegistry.get(spec.type)
            ?: ActionResult.Failure("unknown action: ${spec.type}").let { result ->
                return result to traceFor(index, spec, started, result)
            }
        val expandedArgs = spec.args.mapValues { ctx.variables.expand(it.value) }
        val result = runCatching { action.run(ctx, expandedArgs) }
            .getOrElse { ActionResult.Failure("threw: ${it.message}", it) }
        return result to traceFor(index, spec, started, result)
    }

    private fun traceFor(
        index: Int,
        spec: ActionSpec,
        started: Long,
        result: ActionResult,
    ): ActionExecutionTrace = ActionExecutionTrace(
        index = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        durationMs = System.currentTimeMillis() - started,
        status = when (result) {
            is ActionResult.Success -> ActionTraceStatus.SUCCESS
            is ActionResult.Failure -> ActionTraceStatus.FAILURE
            is ActionResult.Skip -> ActionTraceStatus.SKIPPED
        },
        message = when (result) {
            is ActionResult.Failure -> result.message
            is ActionResult.Skip -> "Skipped"
            is ActionResult.Success -> "Completed"
        },
    )
}

data class TaskRunReport(
    val taskId: Long,
    val taskName: String,
    val startedAt: Long,
    val durationMs: Long,
    val results: List<ActionResult>,
    val traces: List<ActionExecutionTrace>,
    val success: Boolean,
)

enum class ActionTraceStatus {
    SUCCESS,
    FAILURE,
    SKIPPED,
}

data class ActionExecutionTrace(
    val index: Int,
    val actionType: String,
    val label: String,
    val durationMs: Long,
    val status: ActionTraceStatus,
    val message: String,
)

fun ActionExecutionTrace.toSummaryLine(): String =
    "${index + 1}. ${status.name.lowercase()}: $label [$actionType] ${durationMs}ms - $message"

fun List<ActionExecutionTrace>.toRunLogMessage(maxLines: Int = 8): String {
    if (isEmpty()) return "No actions executed"
    val visible = take(maxLines).joinToString("\n") { it.toSummaryLine() }
    val remaining = size - maxLines
    return if (remaining > 0) "$visible\n... $remaining more action(s)" else visible
}
